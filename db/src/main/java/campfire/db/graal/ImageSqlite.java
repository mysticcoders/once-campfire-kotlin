package campfire.db.graal;

import campfire.db.SqlConnection;
import campfire.db.Stmt;
import java.nio.charset.StandardCharsets;
import org.graalvm.nativeimage.PinnedObject;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.function.CFunction.Transition;
import org.graalvm.nativeimage.c.function.CLibrary;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.nativeimage.c.type.WordPointer;
import org.graalvm.word.PointerBase;
import org.graalvm.word.WordFactory;

/**
 * Native-image only: SQLite through the native-image C interface, linked statically from the same
 * amalgamation and flags as the Rust build (`native/build.sh static`). Downcall handles built at
 * run time can't be compiled ahead of time, so the FFM backend runs interpreted in an image; these
 * are direct calls. Binds and column reads skip the thread-state transition, as the FFM backend's
 * critical downcalls do. Steps and resets keep it: they can do I/O, wait out the busy timeout or
 * run a long query, and a call without the transition holds up every safepoint until it returns.
 */
@CLibrary("campfire_sqlite3")
public final class ImageSqlite implements SqlConnection {
    private static final int OK = 0;
    private static final int ROW = 100;
    private static final int DONE = 101;
    private static final int OPEN_FLAGS = 0x2 | 0x4 | 0x40 | 0x8000;
    private static final int PREPARE_PERSISTENT = 0x1;
    private static final long TRANSIENT = -1L;

    private final PointerBase db;

    public ImageSqlite(String path) {
        WordPointer out = StackValue.get(WordPointer.class);
        try (CTypeConversion.CCharPointerHolder name = CTypeConversion.toCString(path)) {
            int rc = sqlite3_open_v2(name.get(), out, OPEN_FLAGS, WordFactory.nullPointer());
            db = out.read();
            if (rc != OK) throw new IllegalStateException("sqlite3_open_v2(" + path + "): " + errmsg());
        }
    }

    @Override
    public Stmt prepare(String sql) {
        WordPointer out = StackValue.get(WordPointer.class);
        try (CTypeConversion.CCharPointerHolder text = CTypeConversion.toCString(sql)) {
            int rc = sqlite3_prepare_v3(db, text.get(), -1, PREPARE_PERSISTENT, out, WordFactory.nullPointer());
            if (rc != OK) throw new IllegalStateException("sqlite3_prepare_v3: " + errmsg() + ": " + sql);
            return new ImageStmt(out.read());
        }
    }

    @Override
    public void execute(String sql) {
        try (CTypeConversion.CCharPointerHolder text = CTypeConversion.toCString(sql)) {
            int rc = sqlite3_exec(db, text.get(), WordFactory.nullPointer(), WordFactory.nullPointer(), WordFactory.nullPointer());
            if (rc != OK) throw new IllegalStateException("sqlite3_exec: " + errmsg() + ": " + sql);
        }
    }

    private String errmsg() {
        return CTypeConversion.toJavaString(sqlite3_errmsg(db));
    }

    /** One prepared statement; used by one thread at a time, as its connection is. */
    private final class ImageStmt implements Stmt {
        private final PointerBase stmt;

        ImageStmt(PointerBase stmt) {
            this.stmt = stmt;
        }

        @Override
        public void bindLong(int index, long value) {
            if (sqlite3_bind_int64(stmt, index, value) != OK) throw new IllegalStateException(errmsg());
        }

        @Override
        public void bindText(int index, String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            try (PinnedObject pinned = PinnedObject.create(bytes)) {
                int rc = sqlite3_bind_text(stmt, index, pinned.addressOfArrayElement(0), bytes.length, WordFactory.pointer(TRANSIENT));
                if (rc != OK) throw new IllegalStateException(errmsg());
            }
        }

        @Override
        public void bindNull(int index) {
            if (sqlite3_bind_null(stmt, index) != OK) throw new IllegalStateException(errmsg());
        }

        @Override
        public boolean step() {
            int rc = sqlite3_step(stmt);
            if (rc == ROW) return true;
            if (rc == DONE) return false;
            throw new IllegalStateException("sqlite3_step: " + rc + " " + errmsg());
        }

        @Override
        public long integer(int column) {
            return sqlite3_column_int64(stmt, column - 1);
        }

        @Override
        public String text(int column) {
            CCharPointer ptr = sqlite3_column_text(stmt, column - 1);
            if (ptr.isNull()) return null;
            int n = sqlite3_column_bytes(stmt, column - 1);
            return CTypeConversion.toJavaString(ptr, WordFactory.unsigned(n), StandardCharsets.UTF_8);
        }

        @Override
        public void reset() {
            sqlite3_reset(stmt);
            sqlite3_clear_bindings(stmt);
        }
    }

    @CFunction
    private static native int sqlite3_open_v2(CCharPointer filename, WordPointer db, int flags, CCharPointer vfs);

    @CFunction
    private static native int sqlite3_prepare_v3(PointerBase db, CCharPointer sql, int nbyte, int flags, WordPointer stmt, WordPointer tail);

    @CFunction
    private static native int sqlite3_exec(PointerBase db, CCharPointer sql, PointerBase callback, PointerBase arg, WordPointer errmsg);

    @CFunction
    private static native CCharPointer sqlite3_errmsg(PointerBase db);

    @CFunction(transition = Transition.NO_TRANSITION)
    private static native int sqlite3_bind_int64(PointerBase stmt, int index, long value);

    @CFunction(transition = Transition.NO_TRANSITION)
    private static native int sqlite3_bind_text(PointerBase stmt, int index, PointerBase text, int n, PointerBase destructor);

    @CFunction(transition = Transition.NO_TRANSITION)
    private static native int sqlite3_bind_null(PointerBase stmt, int index);

    @CFunction
    private static native int sqlite3_step(PointerBase stmt);

    @CFunction(transition = Transition.NO_TRANSITION)
    private static native long sqlite3_column_int64(PointerBase stmt, int column);

    @CFunction(transition = Transition.NO_TRANSITION)
    private static native CCharPointer sqlite3_column_text(PointerBase stmt, int column);

    @CFunction(transition = Transition.NO_TRANSITION)
    private static native int sqlite3_column_bytes(PointerBase stmt, int column);

    @CFunction
    private static native int sqlite3_reset(PointerBase stmt);

    @CFunction(transition = Transition.NO_TRANSITION)
    private static native int sqlite3_clear_bindings(PointerBase stmt);
}
