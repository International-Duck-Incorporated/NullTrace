package net.nulltrace.mod;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Shared constants and utilities for NullTrace - Privacy protection for Minecraft.
 * Protects against client fingerprinting, tracking, and key resolution exploits.
 *
 * Note: This is a client-only mod. The actual initialization happens in
 * {@link net.nulltrace.mod.NullTraceClient#onInitializeClient()}.
 */
public final class NullTrace {
	public static final String MOD_ID = "nulltrace";
	public static final String MOD_NAME = "NullTrace";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Bounded + DiscardPolicy so a probe flood can't grow the queue without bound and OOM.
	private static final ExecutorService LOG_EXECUTOR = new ThreadPoolExecutor(
		1, 1, 0L, TimeUnit.MILLISECONDS,
		new ArrayBlockingQueue<>(1024),
		r -> {
			Thread t = new Thread(r, "NullTrace-Log-Worker");
			t.setDaemon(true);
			return t;
		},
		new ThreadPoolExecutor.DiscardPolicy()
	);

	private NullTrace() {
		// Utility class - prevent instantiation
	}

	/** Off-render-thread LOGGER.info — sync disk write is a server-timeable fingerprint. */
	public static void logInfoAsync(String format, Object... args) {
		LOG_EXECUTOR.execute(() -> LOGGER.info(format, args));
	}

	public static String getVersion() {
		return FabricLoader.getInstance()
			.getModContainer(MOD_ID)
			.map(mod -> mod.getMetadata().getVersion().getFriendlyString())
			.orElse("unknown");
	}
}
