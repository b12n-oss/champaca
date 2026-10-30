import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// ByteBuffer reads and writes at every alignment, in both byte orders, on
// heap and direct buffers: the paths through Unsafe's get/putXUnaligned and
// ScopedMemoryAccess, which champaca overrides (spike 72).
public class UnalignedCheck {
    static void dump(String label, ByteBuffer b) {
        StringBuilder sb = new StringBuilder(label).append(':');
        for (int i = 0; i < b.capacity(); i++) sb.append(' ').append(b.get(i) & 0xff);
        System.out.println(sb);
    }

    static void reads(String label, ByteBuffer b) {
        for (ByteOrder o : new ByteOrder[] {ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN}) {
            b.order(o);
            for (int i = 0; i < 9; i++) {
                System.out.println(label + " " + o + " @" + i + " short " + b.getShort(i) + " char " + (int) b.getChar(i)
                                   + " int " + b.getInt(i) + " long " + b.getLong(i)
                                   + " float " + Float.floatToRawIntBits(b.getFloat(i))
                                   + " double " + Double.doubleToRawLongBits(b.getDouble(i)));
            }
            b.position(1);
            System.out.println(label + " " + o + " relative " + b.getShort() + " " + b.getInt() + " " + b.getLong() + " pos " + b.position());
        }
    }

    static void writes(String label, ByteBuffer b) {
        for (ByteOrder o : new ByteOrder[] {ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN}) {
            b.order(o);
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < b.capacity(); j++) b.put(j, (byte) 0);
                b.putShort(i, (short) -2);
                b.putChar(i + 3, (char) 0xBEEF);
                b.putInt(i + 6, 0x80ABCDEF);
                b.putLong(i + 11, -0x1122334455667788L);
                b.putFloat(i + 20, -1.5f);
                b.putDouble(i + 25, 3.25);
                dump(label + " " + o + " @" + i, b);
            }
            b.position(2);
            b.putShort((short) 0x1234).putInt(-1).putLong(Long.MIN_VALUE);
            dump(label + " " + o + " relative", b);
        }
    }

    public static void main(String[] args) {
        byte[] bytes = new byte[20];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 37 + 200);
        reads("heap", ByteBuffer.wrap(bytes));
        reads("heap-offset", ByteBuffer.wrap(bytes, 3, 17).slice());
        ByteBuffer direct = ByteBuffer.allocateDirect(20);
        direct.put(bytes).flip();
        reads("direct", direct);
        reads("direct-slice", direct.duplicate().position(3).slice());
        writes("heap", ByteBuffer.allocate(40));
        writes("heap-offset", ByteBuffer.wrap(new byte[45], 5, 40).slice());
        writes("direct", ByteBuffer.allocateDirect(40));
        // other arrays as the base: a char[] through a CharBuffer is its own path, so Unsafe over an int[]
        // is checked through a view of a heap buffer, which stays a byte[]
        ByteBuffer views = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        System.out.println("int view " + views.asIntBuffer().get(1) + " " + views.asShortBuffer().get(3) + " " + views.asLongBuffer().get(1)
                           + " " + views.asCharBuffer().get(2) + " " + Float.floatToRawIntBits(views.asFloatBuffer().get(2)));
        // copies between overlapping ranges of one array (Unsafe.copyMemory between two arrays, which
        // ByteBuffer.put(ByteBuffer) and compact() take): forwards and backwards
        ByteBuffer shared = ByteBuffer.wrap(bytes.clone());
        shared.position(0);
        shared.put(ByteBuffer.wrap(shared.array(), 5, 12).slice());
        dump("overlap forward", shared);
        shared = ByteBuffer.wrap(bytes.clone());
        shared.position(6);
        shared.put(ByteBuffer.wrap(shared.array(), 0, 12).slice());
        dump("overlap backward", shared);
        shared = ByteBuffer.wrap(bytes.clone());
        shared.position(7);
        shared.compact();
        dump("compact", shared);
        // a closed arena's segment: the scoped check throws as on the JVM
        Arena arena = Arena.ofConfined();
        MemorySegment seg = arena.allocate(16);
        ByteBuffer sb = seg.asByteBuffer();
        sb.putInt(4, 42);
        System.out.println("segment " + sb.getInt(4) + " " + seg.byteSize());
        arena.close();
        try {
            sb.getInt(4);
            System.out.println("segment read after close: no exception");
        } catch (IllegalStateException e) {
            System.out.println("segment read after close: " + e.getClass().getName() + ": " + e.getMessage());
        }
        try {
            sb.putShort(4, (short) 1);
            System.out.println("segment write after close: no exception");
        } catch (IllegalStateException e) {
            System.out.println("segment write after close: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }
}
