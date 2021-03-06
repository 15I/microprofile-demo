package com.github.phillipkruger.profiling;

import com.github.phillipkruger.profiling.membership.Membership;
import com.github.phillipkruger.profiling.repository.EventLogger;
import com.github.phillipkruger.profiling.repository.EventSearcher;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import lombok.extern.java.Log;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.eclipse.microprofile.opentracing.Traced;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import com.github.phillipkruger.profiling.membership.MembershipProxy;
import java.time.temporal.ChronoUnit;
import java.util.logging.Level;
import javax.annotation.security.RolesAllowed;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.Response.Status;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.elasticsearch.client.transport.NoNodeAvailableException;

/**
 * Profiling Service. JAX-RS
 * @author Phillip Kruger (phillip.kruger@phillip-kruger.com)
 */
@Log
@RequestScoped
@Path("/")
@Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Profile service",description = "Build up a profile of the user")
public class ProfileService {
    
    @Inject
    private EventSearcher eventSearcher;
    
    @Inject
    private EventLogger eventLogger;
    
    @Inject @RestClient
    private MembershipProxy membershipProxy;
    
    @Inject
    private JsonWebToken callerPrincipal;
    
    @POST
    @Operation(description = "Getting all the events for a certain user")
    @APIResponses({
            @APIResponse(responseCode = "202", description = "Accepted the event, queued to be stored"),
            @APIResponse(responseCode = "401", description = "User not authorized")
    })
    @SecurityRequirement(name = "Authorization")
    @RolesAllowed({"admin","user"})
    @Counted(name = "User events created",absolute = true,monotonic = true)
    public Response logEvent(@NotNull @RequestBody(
                                        description = "Log a new event.",
                                        content = @Content(
                                                    mediaType = MediaType.APPLICATION_JSON,
                                                    schema = @Schema(implementation = UserEvent.class))) 
                                UserEvent event){
        
        eventLogger.logEvent(callerPrincipal.getRawToken(), event);
        
        return Response.accepted(event).build();
    }
    
    @GET @Path("user/{userId}")
    @Operation(description = "Getting all the events for a certain user")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Successfull, returning events", 
                    content = @Content(schema = @Schema(implementation = UserEvent.class))),
            @APIResponse(responseCode = "401", description = "User not authorized"),
            @APIResponse(responseCode = "412", description = "Membership not found, invalid userId",
                    headers = @Header(name = REASON)),
            @APIResponse(responseCode = "503", description = "Proplem with a connection to a downstream service",
                    headers = @Header(name = REASON))
    })
    @SecurityRequirement(name = "Authorization")
    @RolesAllowed({"admin","user"})
    @Traced(operationName = "GetUserEvents", value = true)
    @CircuitBreaker(failOn = NoNodeAvailableException.class,requestVolumeThreshold = 1, 
            failureRatio=1, delay = 10, delayUnit = ChronoUnit.SECONDS )
    @Counted(name = "User events requests",absolute = true,monotonic = true)
    public Response getUserEvents(
            @Parameter(name = "userId", description = "The User Id of the member", 
                    required = true, allowEmptyValue = false, example = "1") 
                    @PathParam("userId") int userId, 
            @Parameter(name = "size", description = SIZE_DESC, 
                    required = false, allowEmptyValue = true, example = "10") @DefaultValue("-1") 
                    @QueryParam("size") int size){
        
        try {
            validateMembership(userId);
        } catch (NotFoundException nfe){
            return Response.status(Status.PRECONDITION_FAILED).header(REASON, "Membership [" + userId + "] does not exist").build();
        }
        return eventSearcher.search(UserEventConverter.USER_ID,userId,size);
    }
    
    @GET @Path("event/{eventName}")
    public Response searchEvents(
            @Parameter(name = "eventName", description = "The name of the event", required = true, allowEmptyValue = false, example = "Gym") @PathParam("eventName") String eventName, 
            @Parameter(name = "size", description = SIZE_DESC, required = false, allowEmptyValue = true, example = "10") @DefaultValue("-1") @QueryParam("size") int size) {
        
        return eventSearcher.search(UserEventConverter.EVENT_NAME,eventName,size);
    }
    
    @GET @Path("location/{location}")
    public Response searchLocations(
            @Parameter(name = "location", description = "The location of the event", required = true, allowEmptyValue = false, example = "Johannesburg") @PathParam("location") String location, 
            @Parameter(name = "size", description = SIZE_DESC, required = false, allowEmptyValue = true, example = "10") @DefaultValue("-1") @QueryParam("size") int size) {
        
        return eventSearcher.search(UserEventConverter.LOCATION,location,size);
    }
    
    @GET @Path("partner/{partner}")
    public Response searchPartners(
            @Parameter(name = "partner", description = "The partner at the event", required = true, allowEmptyValue = false, example = "Virgin Active") @PathParam("partner") String partner, 
            @Parameter(name = "size", description = SIZE_DESC, required = false, allowEmptyValue = true, example = "10") @DefaultValue("-1") @QueryParam("size") int size) {
            
        return eventSearcher.search(UserEventConverter.PARTNER_NAME,partner,size);
    }
    
    private void validateMembership(int membershipId) {
        Membership membership = membershipProxy.getMembership("Bearer " + callerPrincipal.getRawToken() , membershipId);
        log.log(Level.FINEST, "Validate membership = [{0}]", membership);
    }
    
    private static final String SIZE_DESC = "Limit the events to return";
    private static final String REASON = "reason";
}
