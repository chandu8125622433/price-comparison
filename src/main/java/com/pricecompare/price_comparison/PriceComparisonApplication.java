package com.pricecompare.price_comparison;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PriceComparisonApplication {

	private static final Logger log =
			LoggerFactory.getLogger(PriceComparisonApplication.class);

	public static void main(String[] args) {
		// Diagnostic: any uncaught exception on ANY thread is logged explicitly,
		// so a fatal Java error can never disappear without a trace.
		Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
				log.error("FATAL: uncaught exception on thread '{}'", thread.getName(), throwable));

		// Diagnostic: this hook runs on graceful shutdown (Ctrl+C, terminal close
		// handled as CTRL_CLOSE_EVENT, actuator shutdown). It does NOT run when the
		// process is forcibly terminated (TerminateProcess), e.g. when the machine
		// sleeps/resumes and the hosting terminal or parent shell tears down the
		// process tree. Presence/absence of this log line therefore distinguishes
		// graceful shutdown from external kill.
		Runtime.getRuntime().addShutdownHook(new Thread(() ->
				log.info("JVM shutdown hook fired - graceful shutdown in progress"),
				"shutdown-diagnostic"));

		SpringApplication.run(PriceComparisonApplication.class, args);
	}

}
