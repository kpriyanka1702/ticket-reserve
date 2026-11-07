package com.ticketreserve;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ticketreserve.ReservationService.ConfirmView;
import com.ticketreserve.ReservationService.EventView;
import com.ticketreserve.ReservationService.HoldView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public final class ReservationResource {

    private final ReservationService service;

    public ReservationResource(ReservationService service) {
        this.service = service;
    }

    @GET
    @Path("/events")
    public List<EventView> list() {
        return service.listEvents();
    }

    @GET
    @Path("/events/{eventId}")
    public EventView get(@PathParam("eventId") long eventId) {
        return service.getEvent(eventId);
    }

    @POST
    @Path("/events/{eventId}/holds")
    public Response hold(@PathParam("eventId") long eventId, @Valid @NotNull HoldRequest request) {
        HoldView hold = service.hold(eventId, request.getQty(), request.getEmail());
        return Response.status(Response.Status.CREATED).entity(hold).build();
    }

    @POST
    @Path("/holds/{id}/confirm")
    public ConfirmView confirm(@PathParam("id") String id) {
        return service.confirm(id);
    }

    @DELETE
    @Path("/holds/{id}")
    public Response cancel(@PathParam("id") String id) {
        if (!service.release(id)) {
            throw new ApiException(404, "not_found", "Hold not found");
        }
        return Response.noContent().build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HoldRequest {
        @NotNull
        @Min(1)
        @Max(8)
        private Integer qty;

        @NotBlank
        @Email
        private String email;

        public Integer getQty() {
            return qty;
        }

        public void setQty(Integer qty) {
            this.qty = qty;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }
    }
}
