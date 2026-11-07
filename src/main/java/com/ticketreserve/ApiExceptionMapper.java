package com.ticketreserve;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ApiExceptionMapper {

    private ApiExceptionMapper() {
    }

    public record ErrorBody(String error, String message) {
    }

    @Provider
    public static final class ClientError implements ExceptionMapper<ApiException> {
        @Override
        public Response toResponse(ApiException exception) {
            return Response.status(exception.getStatus())
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(new ErrorBody(exception.getCode(), exception.getMessage()))
                    .build();
        }
    }

    @Provider
    public static final class Unexpected implements ExceptionMapper<Exception> {
        private static final Logger log = LoggerFactory.getLogger(Unexpected.class);

        @Override
        public Response toResponse(Exception exception) {
            if (exception instanceof WebApplicationException web) {
                return web.getResponse();
            }
            log.error("request failed", exception);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(new ErrorBody("internal_error", "Something went wrong"))
                    .build();
        }
    }
}
