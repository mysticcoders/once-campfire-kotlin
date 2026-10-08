package campfire.kit.graal;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.word.WordFactory;

/**
 * Native-image only: Netty 4.2's {@code CleanerJava24Linker} calls libc malloc/free through
 * downcall handles it links at run time, which native-image can't compile, and Netty picks that
 * cleaner while the image is built. The same calls go through {@link UnmanagedMemory} instead.
 */
@TargetClass(className = "io.netty.util.internal.CleanerJava24Linker")
final class Target_io_netty_util_internal_CleanerJava24Linker {
    @Substitute
    static long malloc(int capacity) {
        long address = UnmanagedMemory.malloc(WordFactory.unsigned(Math.max(capacity, 1L))).rawValue();
        if (address == 0) {
            throw new OutOfMemoryError("malloc(" + capacity + ") failed");
        }
        return address;
    }

    @Substitute
    static void free(long memoryAddress) {
        UnmanagedMemory.free(WordFactory.pointer(memoryAddress));
    }
}
