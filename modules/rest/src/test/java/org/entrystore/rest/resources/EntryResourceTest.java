package org.entrystore.rest.resources;

import static io.restassured.RestAssured.delete;
import static io.restassured.RestAssured.get;
import static io.restassured.RestAssured.preemptive;
import static org.assertj.core.api.Assertions.assertThat;
import static org.restlet.data.Status.CLIENT_ERROR_NOT_FOUND;
import static org.restlet.data.Status.SUCCESS_NO_CONTENT;

import io.restassured.RestAssured;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class EntryResourceTest {

	@Test
	@Disabled("Needs a live EntryStore instance running on localhost:8181")
	void removeRepresentationsOnLiveInstance() {

		String uri = "http://localhost:8181/_principals/entry/1";
		RestAssured.authentication = preemptive().basic("admin", "enter-password-here");

		ExecutorService executor = Executors.newFixedThreadPool(2);
		List<Integer> resultCodes = Collections.synchronizedList(new ArrayList<>());
		for (int i = 0; i < 2; i++) {
			final int run = i;
			executor.execute(() -> resultCodes.add(delete(uri).then().extract().statusCode()));
		}
		executor.shutdown();
		while (!executor.isTerminated()) {}

		assertThat(resultCodes).containsExactlyInAnyOrder(SUCCESS_NO_CONTENT.getCode(), CLIENT_ERROR_NOT_FOUND.getCode());

		get(uri).then().statusCode(CLIENT_ERROR_NOT_FOUND.getCode());
	}
}
