package qupath.ext.mantis.mrxsconvert.settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.management.ManagementFactory;

/**
 * Limits of this machine for the Advanced settings (spec 06): parallel files ≤ cores, heap ≤ physical RAM.
 *
 * @param cores               logical processors
 * @param physicalMemoryBytes total RAM, or 0 if it couldn't be read (then the heap has no upper limit)
 */
public record MachineLimits(int cores, long physicalMemoryBytes) {

	private static final Logger logger = LoggerFactory.getLogger(MachineLimits.class);
	private static final long MIB = 1024 * 1024;

	private static volatile MachineLimits current;

	public static MachineLimits current() {
		MachineLimits limits = current;
		if (limits == null) {
			limits = new MachineLimits(Runtime.getRuntime().availableProcessors(), readPhysicalMemory());
			current = limits;
		}
		return limits;
	}

	public int maxParallelFiles() {
		return Math.max(1, cores);
	}

	/** Physical RAM in MiB, or {@link Integer#MAX_VALUE} if unknown. */
	public int maxHeapMb() {
		return physicalMemoryBytes <= 0 ? Integer.MAX_VALUE : (int) Math.min(Integer.MAX_VALUE, physicalMemoryBytes / MIB);
	}

	private static long readPhysicalMemory() {
		try {
			return mxBeanPhysicalMemory();
		} catch (LinkageError e) {
			// QuPath's runtime is jlink'd without the jdk.management module (spike 01)
			logger.debug("com.sun.management is not available; asking Windows for the RAM size");
		}
		try {
			return windowsPhysicalMemory();
		} catch (Throwable e) {
			logger.warn("Could not read the amount of RAM; heap size will not be limited", e);
			return 0;
		}
	}

	static long mxBeanPhysicalMemory() {
		return ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os
				? os.getTotalMemorySize() : 0;
	}

	/** {@code GlobalMemoryStatusEx}; QuPath enables native access for extensions. */
	static long windowsPhysicalMemory() throws Throwable {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment function = SymbolLookup.libraryLookup("kernel32", arena).find("GlobalMemoryStatusEx")
					.orElseThrow();
			MethodHandle handle = Linker.nativeLinker().downcallHandle(function,
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
			// MEMORYSTATUSEX: DWORD dwLength, DWORD dwMemoryLoad, DWORDLONG ullTotalPhys, ... (64 bytes)
			MemorySegment status = arena.allocate(64, 8);
			status.set(ValueLayout.JAVA_INT, 0, 64);
			int ok = (int) handle.invokeExact(status);
			if (ok == 0)
				throw new IllegalStateException("GlobalMemoryStatusEx failed");
			return status.get(ValueLayout.JAVA_LONG, 8);
		}
	}
}
