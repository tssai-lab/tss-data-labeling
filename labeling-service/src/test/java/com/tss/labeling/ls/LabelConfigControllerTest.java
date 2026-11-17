package com.tss.labeling.ls;

import com.tss.labeling.identity.ActorService;
import com.tss.labeling.identity.IdentityClient;
import com.tss.labeling.identity.IdentityUser;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpStatus.FORBIDDEN;

class LabelConfigControllerTest {
    @Test void authenticationRunsBeforeSourceCode() {
        IdentityClient identity = new IdentityClient(RestClient.builder()
                .requestFactory(new SimpleClientHttpRequestFactory()), "http://127.0.0.1:1");
        // A null adapter would throw if reached. Missing login must fail first,
        // in the existing identity guard, without contacting any HTTP service.
        var controller = new LabelConfigController(null, identity, null,null);
        var error = assertThrows(ResponseStatusException.class, () -> controller.inspect(null,
                new LabelConfigController.ConfigRequest("<View/>"), null));
        assertEquals(401, error.getStatusCode().value());
    }

    @Test void disabledActorCannotInvokeSourceCode() {
        IdentityClient identity = new IdentityClient(RestClient.builder()
                .requestFactory(new SimpleClientHttpRequestFactory()), "http://127.0.0.1:1") {
            @Override public IdentityUser currentUser(String auth) { return new IdentityUser("1", "user"); }
        };
        ActorService actors = new ActorService(null) {
            @Override public ActorContext resolve(IdentityUser user) { throw new ResponseStatusException(FORBIDDEN); }
        };
        var controller = new LabelConfigController(null, identity, actors,null);
        var error = assertThrows(ResponseStatusException.class, () -> controller.inspect("test",
                new LabelConfigController.ConfigRequest("<View/>"), null));
        assertEquals(403, error.getStatusCode().value());
    }
}
