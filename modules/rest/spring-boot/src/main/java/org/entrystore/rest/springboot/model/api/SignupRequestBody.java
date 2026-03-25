package org.entrystore.rest.springboot.model.api;

import com.fasterxml.jackson.annotation.JsonSetter;

public record SignupRequestBody(
		String email,
		String password,
		@JsonSetter("firstname") String firstName,
		@JsonSetter("lastname") String lastName,
		@JsonSetter("urlsuccess") String urlSuccess,
		@JsonSetter("urlfailure") String urlFailure,
		@JsonSetter("grecaptcharesponse") String rcResponseV2) {
}

