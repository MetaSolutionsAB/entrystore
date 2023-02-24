package org.entrystore.rest.util;

import static org.junit.jupiter.api.Assertions.*;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class HttpUtilTest {

	@Test
	void isLargerThanRepresentationIsNull() {
		Assertions.assertThat(HttpUtil.isLargerThan(null, 1)).isFalse();
	}
}
