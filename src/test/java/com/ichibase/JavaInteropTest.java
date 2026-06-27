package com.ichibase;

import kotlinx.serialization.json.JsonElement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves the SDK is usable from plain Java via the callback layer — this file is
 * Java, not Kotlin. (Network calls aren't exercised here; the point is that the
 * Java-facing API + SAM lambdas compile and instantiate cleanly.)
 */
public class JavaInteropTest {

    @Test
    public void constructsAndBuildsAndAcceptsLambdas() {
        IchibaseJava ichi = IchibaseJava.create("https://demo.ichibase.net", "ich_pub_demo");

        // Build a PostgREST query with the normal chain — from Java.
        PostgrestQueryBuilder q = ichi.from("posts")
                .select("id, title")
                .eq("published", true)
                .order("created_at", false)
                .limit(10);
        assertNotNull(q);

        // SAM fun interfaces are plain Java lambdas (compilation is the assertion).
        IchibaseCallback<JsonElement> onResult = res -> {
            boolean ok = res.getOk();
            JsonElement data = res.getData();
        };
        IchibaseValueCallback<JsonElement> onUser = user -> { };
        RealtimeListener onMessage = msg -> { };
        JavaAuthStateListener onAuth = (event, session) -> { };
        assertNotNull(onResult);
        assertNotNull(onUser);
        assertNotNull(onMessage);
        assertNotNull(onAuth);

        // Sub-clients resolve.
        assertNotNull(ichi.auth());
        assertNotNull(ichi.mongo().collection("users"));
        assertNotNull(ichi.functions());
        assertNotNull(ichi.realtime());

        ichi.close();
    }

    @Test
    public void rejectsServiceKey() {
        assertThrows(IllegalArgumentException.class,
                () -> IchibaseJava.create("https://demo.ichibase.net", "ich_admin_secret"));
    }
}
