// Copyright (C) 2026 champaca contributors
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

// The three copies behind decoding (StringUTF16.compress char[] -> byte[],
// StringLatin1.inflate byte[] -> char[], StringCoding.countPositives),
// through the String API and called directly, ranges out of bounds included:
// what was copied before the exception and its text
public class StringDecodeCheck {
  static Method compress, inflate, countPositives;

  static String call(Method m, Object... args) {
    try { return String.valueOf(m.invoke(null, args)); }
    catch (InvocationTargetException e) { return e.getCause().toString(); }
    catch (Exception e) { return e.toString(); }
  }

  static void compressRow(String name, char[] src, int so, int dlen, int doff, int len) {
    byte[] dst = new byte[dlen];
    String r = call(compress, src, so, dst, doff, len);
    System.out.println("compress " + name + ": " + r + " " + Arrays.toString(dst));
  }

  static void inflateRow(String name, byte[] src, int so, int dlen, int doff, int len) {
    char[] dst = new char[dlen];
    String r = call(inflate, src, so, dst, doff, len);
    int[] codes = new int[dlen];
    for (int i = 0; i < dlen; i++) codes[i] = dst[i];
    System.out.println("inflate " + name + ": " + r + " " + Arrays.toString(codes));
  }

  static void countRow(String name, byte[] ba, int off, int len) {
    System.out.println("countPositives " + name + ": " + call(countPositives, ba, off, len));
  }

  public static void main(String[] args) throws Exception {
    Class<?> u = Class.forName("java.lang.StringUTF16"), l = Class.forName("java.lang.StringLatin1"),
             c = Class.forName("java.lang.StringCoding");
    compress = u.getDeclaredMethod("compress", char[].class, int.class, byte[].class, int.class, int.class);
    inflate = l.getDeclaredMethod("inflate", byte[].class, int.class, char[].class, int.class, int.class);
    countPositives = c.getDeclaredMethod("countPositives", byte[].class, int.class, int.class);
    compress.setAccessible(true); inflate.setAccessible(true); countPositives.setAccessible(true);

    char[] latin = {'a', 'b', 0x7f, 0x80, 0xff, 'z'};
    char[] mixed = {'a', 'b', 0x100, 'c'};
    compressRow("latin", latin, 0, 6, 0, 6);
    compressRow("offsets", latin, 2, 6, 1, 4);
    compressRow("non-latin stops", mixed, 0, 4, 0, 4);
    compressRow("zero", latin, 0, 2, 0, 0);
    compressRow("negative len", latin, 0, 2, 0, -3);
    compressRow("src past end", latin, 3, 6, 0, 5);
    compressRow("dst past end", latin, 0, 3, 0, 6);
    compressRow("non-latin before dst end", mixed, 0, 2, 0, 4);
    compressRow("negative src", latin, -1, 6, 0, 2);
    compressRow("negative dst", latin, 0, 6, -2, 2);
    System.out.println("compress null: " + call(compress, null, 0, new byte[1], 0, 1));
    System.out.println("compress null dst: " + call(compress, latin, 0, null, 0, 1));
    System.out.println("compress null dst, non-latin first: " + call(compress, mixed, 2, null, 0, 1));
    System.out.println("compress nulls, len 0: " + call(compress, null, 0, null, 0, 0));

    byte[] bytes = {'h', 'i', (byte) 0x80, (byte) 0xff, 0x7f, (byte) -1};
    inflateRow("all", bytes, 0, 6, 0, 6);
    inflateRow("offsets", bytes, 1, 6, 2, 3);
    inflateRow("zero", bytes, 0, 2, 0, 0);
    inflateRow("negative len", bytes, 0, 2, 0, -1);
    inflateRow("src past end", bytes, 4, 6, 0, 4);
    inflateRow("dst past end", bytes, 0, 3, 1, 5);
    inflateRow("negative src", bytes, -2, 4, 0, 3);
    System.out.println("inflate null: " + call(inflate, bytes, 0, null, 0, 1));
    System.out.println("inflate null src: " + call(inflate, null, 0, null, 0, 1));
    System.out.println("inflate nulls, len 0: " + call(inflate, null, 0, null, 0, 0));

    byte[] ascii = {'a', 'b', 'c', (byte) 0x80, 'd'};
    countRow("first negative", ascii, 0, 5);
    countRow("after offset", ascii, 1, 4);
    countRow("all positive", ascii, 0, 3);
    countRow("from the negative", ascii, 3, 2);
    countRow("zero", ascii, 2, 0);
    countRow("negative len", ascii, 2, -4);
    countRow("past end", new byte[] {'a', 'b'}, 0, 5);
    countRow("negative off", ascii, -1, 3);
    countRow("int overflow", ascii, 3, Integer.MAX_VALUE);
    System.out.println("countPositives null: " + call(countPositives, null, 0, 1));
    System.out.println("countPositives null, len 0: " + call(countPositives, null, 0, 0));

    // through the String API
    char[] big = new char[5000];
    for (int i = 0; i < big.length; i++) big[i] = (char) (i % 256);
    String s = new String(big);
    System.out.println("String(char[]) latin " + s.length() + " " + s.hashCode() + " " + (int) s.charAt(4999));
    big[4000] = 'ā';
    String t = new String(big, 100, 4500);
    System.out.println("String(char[]) mixed " + t.length() + " " + t.hashCode() + " " + (int) t.charAt(3900));
    byte[] text = "plain ascii, then café and more".getBytes(StandardCharsets.UTF_8);
    String d = new String(text, StandardCharsets.UTF_8);
    System.out.println("UTF-8 decode " + d.length() + " " + d);
    char[] out = new char[8];
    "abcdefgh".getChars(2, 6, out, 1);
    System.out.println("getChars " + new String(out, 0, 6).replace('\0', '_'));
    StringBuilder sb = new StringBuilder("xy");
    sb.append(new char[] {'1', 0x2603, '2'});
    System.out.println("StringBuilder " + sb + " " + sb.length());
  }
}
