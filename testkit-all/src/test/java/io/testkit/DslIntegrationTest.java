package io.testkit;

import org.junit.jupiter.api.Test;

class DslIntegrationTest {

    @Test
    void contextFlowsAcrossSteps() {
        // Step 1 (mock): spin up a WireMock server on port 9191 with two stubs
        // Step 2 (api):  GET /users/1  → extract teamId from response into ctx
        // Step 3 (api):  GET /teams/{id} using ctx.get("teamId") as the path param
        //
        // This exercises:
        //   - MockStep starting a server and storing its URL in context
        //   - ApiStep reading suite-level baseUrl from TestKitConfig
        //   - ctx.resolve() doing late-binding on the pathParam lambda
        //   - TestKitRunner threading the same ctx through all three steps

        TestKitDsl.test("User → Team context flow")
                .config(c -> c
                        .baseUrl("http://localhost:9191")
                        .failFast(true))

                .mock(m -> m
                        .port(9191)
                        .stub(s -> s
                                .GET("/users/1")
                                .willReturn(200, """
                                        {"id":"1","name":"Alice","teamId":"t42"}
                                        """))
                        .stub(s -> s
                                .GET("/teams/t42")
                                .willReturn(200, """
                                        {"id":"t42","name":"Engineering"}
                                        """)))

                .api("Fetch user", a -> a
                        .GET("/users/1")
                        .expect(200)
                        .extract("teamId", "$.teamId"))   // writes "t42" into ctx

                .api("Fetch team", a -> a
                        .GET("/teams/{id}")
                        .pathParam("id", ctx -> ctx.get("teamId"))  // reads "t42" from ctx at runtime
                        .expect(200)
                        .assertJsonPath("$.name", "Engineering"))

                .runAndAssert();
    }
}