package com.fnproject.fn.jrestless;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fnproject.fn.api.InputEvent;
import com.fnproject.fn.api.RuntimeContext;
import com.jrestless.core.container.dpi.InstanceBinder;
import jersey.repackaged.com.google.common.collect.ImmutableMap;
import org.glassfish.hk2.utilities.Binder;
import org.glassfish.jersey.server.ResourceConfig;
import org.junit.Before;
import org.junit.Test;

import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

// Currently many of these features are not available in the functions platform
// They are tested here so as to check that the current implementation of jrestless works as anticipated
public class FnFutureRequestHandlerTest {
    private String DOMAIN_WITH_SCHEME = "http://www.example.com";
    private FnTestObjectHandler handler;
    private TestService testService;
    private RuntimeContext runtimeContext = mock(RuntimeContext.class);
    private ByteArrayInputStream defaultBody;

    @Before
    public void setUp() {
        testService = mock(TestService.class);
        handler = createAndStartHandler(new ResourceConfig(), testService);
        defaultBody = new ByteArrayInputStream(new byte[]{});
    }

    private interface TestService {
        void testRoundTrip();
        void testIncorrectRoute();
    }

    private FnTestObjectHandler createAndStartHandler(ResourceConfig config, TestService testService) {
        Binder binder = new InstanceBinder.Builder().addInstance(testService, TestService.class).build();
        config.register(binder);
        config.register(TestResource.class);
        config.register(SomeCheckedAppExceptionMapper.class);
        config.register(SomeUncheckedAppExceptionMapper.class);
        config.register(GlobalExceptionMapper.class);
        FnTestObjectHandler handler = new FnTestObjectHandler();
        handler.init(config);
        handler.start();
        handler.setRuntimeContext(runtimeContext);
        return handler;
    }

    // Note: This test is important and though the features used to test it aren't currently available it should
    // still work. Using WrappedOutput makes testing this much easier.
    @Test
    public void testRoundTrip() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> inputHeaders = ImmutableMap.of(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON,
                HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON);
        String contents = mapper.writeValueAsString(new AnObject("123"));
        ByteArrayInputStream body = new ByteArrayInputStream(contents.getBytes());

        InputEvent inputEvent = new DefaultInputEvent()
                .setReqUrlAndRoute(DOMAIN_WITH_SCHEME + "/round-trip", "/round-trip")
                .setMethod("POST")
                .setHeaders(inputHeaders)
                .setBody(body)
                .getInputEvent();
        FnRequestHandler.WrappedOutput wrappedOutput = handler.handleTestRequest(inputEvent);

        assertEquals(200, wrappedOutput.statusCode);
        assertEquals(contents, wrappedOutput.body);
    }

    // Note: This is a feature that is yet to be implemented in the Functions platform
    // The Functions platform currently only returns status codes of 200 or 500
    @Test
    public void testIncorrectRoute() {
        InputEvent inputEvent = new DefaultInputEvent()
                .setReqUrlAndRoute(DOMAIN_WITH_SCHEME + "/unspecified/route", "/unspecified/route")
                .setMethod("GET")
                .getInputEvent();

        FnRequestHandler.WrappedOutput wrappedOutput = handler.handleTestRequest(inputEvent);
        assertEquals(404, wrappedOutput.statusCode);
    }

    @Test
    public void testSpecificCheckedException() {
        testException("/specific-checked-exception", SomeCheckedAppExceptionMapper.class);
    }

    @Test
    public void testSpecificUncheckedException() {
        testException("/specific-unchecked-exception", SomeUncheckedAppExceptionMapper.class);
    }

    @Test
    public void testUnspecificCheckedException() {
        testException("/unspecific-checked-exception", GlobalExceptionMapper.class);
    }

    @Test
    public void testUnspecificUncheckedException() {
        testException("/unspecific-unchecked-exception", GlobalExceptionMapper.class);
    }

    private void testException(String resource, Class<? extends ExceptionMapper<?>> exceptionMapper) {
        InputEvent inputEvent = new DefaultInputEvent()
                .setReqUrlAndRoute(DOMAIN_WITH_SCHEME + resource, resource)
                .setMethod("GET")
                .getInputEvent();

        FnRequestHandler.WrappedOutput wrappedOutput = handler.handleTestRequest(inputEvent);
        assertEquals(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), wrappedOutput.statusCode);
        assertEquals(exceptionMapper.getSimpleName(), wrappedOutput.body);
    }

    @Test
    public void testOnRequestFailureWithWrappedOutput() {
        FnRequestHandler.WrappedInput wrappedInput = new FnRequestHandler.WrappedInput(
                new DefaultInputEvent().getInputEvent(),
                new ByteArrayInputStream(new byte[]{}));
        RuntimeException exception = new RuntimeException("Testing that onRequestFailure works");

        FnRequestHandler.WrappedOutput output = handler.onRequestFailure(exception, wrappedInput, null);
        assertEquals(null, output.body);
        assertEquals(500, output.statusCode);
    }

    @Path("/")
    @Singleton // singleton in order to test proxies
    public static class TestResource {

        @Path("/round-trip")
        @POST
        public Response putSomething(AnObject entity) {
            return Response.ok(entity).build();
        }

        @Path("specific-checked-exception")
        @GET
        public void throwSpecificCheckedException() throws SomeCheckedAppException {
            throw new SomeCheckedAppException();
        }

        @Path("specific-unchecked-exception")
        @GET
        public void throwSpecificUncheckedException() {
            throw new SomeUncheckedAppException();
        }

        @Path("unspecific-checked-exception")
        @GET
        public void throwUnspecificCheckedException() throws FileNotFoundException {
            throw new FileNotFoundException();
        }

        @Path("unspecific-unchecked-exception")
        @GET
        public void throwUnspecificUncheckedException() {
            throw new RuntimeException();
        }
    }

    public static class SomeCheckedAppException extends Exception {
        private static final long serialVersionUID = 1L;
    }


    public static class SomeUncheckedAppException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    @Provider
    public static class SomeCheckedAppExceptionMapper implements ExceptionMapper<SomeCheckedAppException> {
        @Override
        public Response toResponse(SomeCheckedAppException exception) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(SomeCheckedAppExceptionMapper.class.getSimpleName()).build();
        }
    }

    @Provider
    public static class SomeUncheckedAppExceptionMapper implements ExceptionMapper<SomeUncheckedAppException> {
        @Override
        public Response toResponse(SomeUncheckedAppException exception) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(SomeUncheckedAppExceptionMapper.class.getSimpleName()).build();
        }
    }

    @Provider
    public static class GlobalExceptionMapper implements ExceptionMapper<Exception> {
        @Override
        public Response toResponse(Exception exception) {
            if(exception instanceof WebApplicationException) {
                WebApplicationException wae = (WebApplicationException) exception;
                return wae.getResponse();
            }

            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(GlobalExceptionMapper.class.getSimpleName())
                    .build();
        }
    }

    private static class AnObject {
        private String value;

        @JsonCreator
        private AnObject(@JsonProperty("value") String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }
}