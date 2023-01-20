//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package org.entrystore.rest.micrometer;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;

public class EntryStoreSimpleMeterRegistry extends SimpleMeterRegistry {

	public EntryStoreSimpleMeterRegistry() {
		super();
	}

	public EntryStoreSimpleMeterRegistry(SimpleConfig config, Clock clock) {
		super(config, clock);
	}

	@Override
	protected DistributionStatisticConfig defaultHistogramConfig() {
		DistributionStatisticConfig config = DistributionStatisticConfig.builder()
				.percentiles(0.95d)
				.percentilePrecision(3)
				.percentilesHistogram(false)
				.minimumExpectedValue(1.0)
				.maximumExpectedValue(Double.POSITIVE_INFINITY)
				.expiry(Duration.ofMinutes(1))
				.bufferLength(3)
				.build();
		return config;
	}
}
