package com.qq.taf.jce;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class JceUtil {
    private static final byte[] highDigits;
    private static final int iConstant = 37;
    private static final int iTotal = 17;
    private static final byte[] lowDigits;

    static {
        byte[] bArr = {48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 65, 66, 67, 68, 69, 70};
        byte[] bArr2 = new byte[256];
        byte[] bArr3 = new byte[256];
        for (int i = 0; i < 256; i++) {
            bArr2[i] = bArr[i >>> 4];
            bArr3[i] = bArr[i & 15];
        }
        highDigits = bArr2;
        lowDigits = bArr3;
    }

    public static int compareTo(byte b2, byte b3) {
        if (b2 < b3) {
            return -1;
        }
        return b2 > b3 ? 1 : 0;
    }

    public static boolean equals(byte b2, byte b3) {
        return b2 == b3;
    }

    public static String getHexdump(byte[] bArr) {
        return getHexdump(ByteBuffer.wrap(bArr));
    }

    public static byte[] getJceBufArray(ByteBuffer byteBuffer) {
        int position = byteBuffer.position();
        byte[] bArr = new byte[position];
        System.arraycopy(byteBuffer.array(), 0, bArr, 0, position);
        return bArr;
    }

    public static int hashCode(byte b2) {
        return b2 + 629;
    }

    public static int compareTo(char c2, char c3) {
        if (c2 < c3) {
            return -1;
        }
        return c2 > c3 ? 1 : 0;
    }

    public static boolean equals(char c2, char c3) {
        return c2 == c3;
    }

    public static String getHexdump(ByteBuffer byteBuffer) {
        int remaining = byteBuffer.remaining();
        if (remaining == 0) {
            return "empty";
        }
        StringBuffer stringBuffer = new StringBuffer((byteBuffer.remaining() * 3) - 1);
        int position = byteBuffer.position();
        int i = byteBuffer.get() & 255;
        stringBuffer.append((char) highDigits[i]);
        stringBuffer.append((char) lowDigits[i]);
        while (true) {
            remaining--;
            if (remaining > 0) {
                stringBuffer.append(' ');
                int i2 = byteBuffer.get() & 255;
                stringBuffer.append((char) highDigits[i2]);
                stringBuffer.append((char) lowDigits[i2]);
            } else {
                byteBuffer.position(position);
                return stringBuffer.toString();
            }
        }
    }

    public static int hashCode(char c2) {
        return c2 + 629;
    }

    public static int compareTo(double d, double d2) {
        if (d < d2) {
            return -1;
        }
        return d > d2 ? 1 : 0;
    }

    public static boolean equals(double d, double d2) {
        return d == d2;
    }

    public static int hashCode(int i) {
        return i + 629;
    }

    public static int compareTo(float f, float f2) {
        if (f < f2) {
            return -1;
        }
        return f > f2 ? 1 : 0;
    }

    public static boolean equals(float f, float f2) {
        return f == f2;
    }

    public static int hashCode(long j2) {
        return ((int) (j2 ^ (j2 >> 32))) + 629;
    }

    public static int compareTo(int i, int i2) {
        if (i < i2) {
            return -1;
        }
        return i > i2 ? 1 : 0;
    }

    public static boolean equals(int i, int i2) {
        return i == i2;
    }

    public static int hashCode(short s) {
        return s + 629;
    }

    public static int compareTo(long j2, long j3) {
        if (j2 < j3) {
            return -1;
        }
        return j2 > j3 ? 1 : 0;
    }

    public static boolean equals(long j2, long j3) {
        return j2 == j3;
    }

    public static int hashCode(boolean z2) {
        return (!z2 ? 1 : 0) + 629;
    }

    public static int compareTo(short s, short s2) {
        if (s < s2) {
            return -1;
        }
        return s > s2 ? 1 : 0;
    }

    public static boolean equals(short s, short s2) {
        return s == s2;
    }

    public static int hashCode(boolean[] zArr) {
        if (zArr == null) {
            return 629;
        }
        int i = 17;
        for (boolean z2 : zArr) {
            i = (i * 37) + (!z2 ? 1 : 0);
        }
        return i;
    }

    public static int compareTo(boolean z2, boolean z3) {
        return (z2 ? 1 : 0) - (z3 ? 1 : 0);
    }

    public static boolean equals(boolean z2, boolean z3) {
        return z2 == z3;
    }

    public static <T extends Comparable<T>> int compareTo(T t, T t2) {
        return t.compareTo(t2);
    }

    public static boolean equals(Object obj, Object obj2) {
        if (obj == null) {
            return obj == obj2;
        }
        return obj.equals(obj2);
    }

    public static int hashCode(byte[] bArr) {
        if (bArr == null) {
            return 629;
        }
        int i = 17;
        for (byte b2 : bArr) {
            i = (i * 37) + b2;
        }
        return i;
    }

    public static <T extends Comparable<T>> int compareTo(List<T> list, List<T> list2) {
        Iterator<T> it = list.iterator();
        Iterator<T> it2 = list2.iterator();
        while (it.hasNext() && it2.hasNext()) {
            int compareTo = it.next().compareTo(it2.next());
            if (compareTo != 0) {
                return compareTo;
            }
        }
        return compareTo(it.hasNext(), it2.hasNext());
    }

    public static int hashCode(char[] cArr) {
        if (cArr == null) {
            return 629;
        }
        int i = 17;
        for (char c2 : cArr) {
            i = (i * 37) + c2;
        }
        return i;
    }

    public static int hashCode(double d) {
        return hashCode(Double.doubleToLongBits(d));
    }

    public static <T extends Comparable<T>> int compareTo(T[] tArr, T[] tArr2) {
        int i = 0;
        for (int i2 = 0; i < tArr.length && i2 < tArr2.length; i2++) {
            int compareTo = tArr[i].compareTo(tArr2[i2]);
            if (compareTo != 0) {
                return compareTo;
            }
            i++;
        }
        return compareTo(tArr.length, tArr2.length);
    }

    public static int hashCode(double[] dArr) {
        if (dArr == null) {
            return 629;
        }
        int i = 17;
        for (int i2 = 0; i2 < dArr.length; i2++) {
            i = (i * 37) + ((int) (Double.doubleToLongBits(dArr[i2]) ^ (Double.doubleToLongBits(dArr[i2]) >> 32)));
        }
        return i;
    }

    public static int hashCode(float f) {
        return Float.floatToIntBits(f) + 629;
    }

    public static int compareTo(boolean[] zArr, boolean[] zArr2) {
        int i = 0;
        for (int i2 = 0; i < zArr.length && i2 < zArr2.length; i2++) {
            int compareTo = compareTo(zArr[i], zArr2[i2]);
            if (compareTo != 0) {
                return compareTo;
            }
            i++;
        }
        return compareTo(zArr.length, zArr2.length);
    }

    public static int hashCode(float[] fArr) {
        if (fArr == null) {
            return 629;
        }
        int i = 17;
        for (float f : fArr) {
            i = (i * 37) + Float.floatToIntBits(f);
        }
        return i;
    }

    public static int hashCode(short[] sArr) {
        if (sArr == null) {
            return 629;
        }
        int i = 17;
        for (short s : sArr) {
            i = (i * 37) + s;
        }
        return i;
    }

    public static int compareTo(byte[] bArr, byte[] bArr2) {
        int i = 0;
        for (int i2 = 0; i < bArr.length && i2 < bArr2.length; i2++) {
            int compareTo = compareTo(bArr[i], bArr2[i2]);
            if (compareTo != 0) {
                return compareTo;
            }
            i++;
        }
        return compareTo(bArr.length, bArr2.length);
    }

    public static int hashCode(int[] iArr) {
        if (iArr == null) {
            return 629;
        }
        int i = 17;
        for (int i2 : iArr) {
            i = (i * 37) + i2;
        }
        return i;
    }

    public static int compareTo(char[] cArr, char[] cArr2) {
        int i = 0;
        for (int i2 = 0; i < cArr.length && i2 < cArr2.length; i2++) {
            int compareTo = compareTo(cArr[i], cArr2[i2]);
            if (compareTo != 0) {
                return compareTo;
            }
            i++;
        }
        return compareTo(cArr.length, cArr2.length);
    }

    public static int hashCode(long[] jArr) {
        if (jArr == null) {
            return 629;
        }
        int i = 17;
        for (long j2 : jArr) {
            i = (i * 37) + ((int) (j2 ^ (j2 >> 32)));
        }
        return i;
    }

    public static int hashCode(JceStruct[] jceStructArr) {
        if (jceStructArr == null) {
            return 629;
        }
        int i = 17;
        for (JceStruct jceStruct : jceStructArr) {
            i = (i * 37) + jceStruct.hashCode();
        }
        return i;
    }

    public static int compareTo(short[] sArr, short[] sArr2) {
        int i = 0;
        for (int i2 = 0; i < sArr.length && i2 < sArr2.length; i2++) {
            int compareTo = compareTo(sArr[i], sArr2[i2]);
            if (compareTo != 0) {
                return compareTo;
            }
            i++;
        }
        return compareTo(sArr.length, sArr2.length);
    }

    public static int hashCode(Object obj) {
        if (obj == null) {
            return 629;
        }
        if (obj.getClass().isArray()) {
            if (obj instanceof long[]) {
                return hashCode((long[]) obj);
            }
            if (obj instanceof int[]) {
                return hashCode((int[]) obj);
            }
            if (obj instanceof short[]) {
                return hashCode((short[]) obj);
            }
            if (obj instanceof char[]) {
                return hashCode((char[]) obj);
            }
            if (obj instanceof byte[]) {
                return hashCode((byte[]) obj);
            }
            if (obj instanceof double[]) {
                return hashCode((double[]) obj);
            }
            if (obj instanceof float[]) {
                return hashCode((float[]) obj);
            }
            if (obj instanceof boolean[]) {
                return hashCode((boolean[]) obj);
            }
            if (obj instanceof JceStruct[]) {
                return hashCode((JceStruct[]) obj);
            }
            return hashCode((Object[]) obj);
        }
        if (obj instanceof JceStruct) {
            return obj.hashCode();
        }
        return obj.hashCode() + 629;
    }

    public static int compareTo(int[] iArr, int[] iArr2) {
        int i = 0;
        for (int i2 = 0; i < iArr.length && i2 < iArr2.length; i2++) {
            int compareTo = compareTo(iArr[i], iArr2[i2]);
            if (compareTo != 0) {
                return compareTo;
            }
            i++;
        }
        return compareTo(iArr.length, iArr2.length);
    }

    public static int compareTo(long[] jArr, long[] jArr2) {
        int i = 0;
        for (int i2 = 0; i < jArr.length && i2 < jArr2.length; i2++) {
            int compareTo = compareTo(jArr[i], jArr2[i2]);
            if (compareTo != 0) {
                return compareTo;
            }
            i++;
        }
        return compareTo(jArr.length, jArr2.length);
    }

    public static int compareTo(float[] fArr, float[] fArr2) {
        int i = 0;
        for (int i2 = 0; i < fArr.length && i2 < fArr2.length; i2++) {
            int compareTo = compareTo(fArr[i], fArr2[i2]);
            if (compareTo != 0) {
                return compareTo;
            }
            i++;
        }
        return compareTo(fArr.length, fArr2.length);
    }

    public static int compareTo(double[] dArr, double[] dArr2) {
        int i = 0;
        for (int i2 = 0; i < dArr.length && i2 < dArr2.length; i2++) {
            int compareTo = compareTo(dArr[i], dArr2[i2]);
            if (compareTo != 0) {
                return compareTo;
            }
            i++;
        }
        return compareTo(dArr.length, dArr2.length);
    }
}
