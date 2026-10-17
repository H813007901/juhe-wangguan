package com.qq.taf.jce;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Array;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class JceInputStream {
    private ByteBuffer bs;
    protected String sServerEncoding = "GBK";

    /* compiled from: ProGuard */
    /* loaded from: classes2.dex */
    public static class HeadData {
        public int tag;
        public byte type;

        public void clear() {
            this.type = (byte) 0;
            this.tag = 0;
        }
    }

    public JceInputStream() {
    }

    private HeadData readHead() {
        HeadData headData = new HeadData();
        readHead(headData);
        return headData;
    }

    private int peakHead(HeadData headData) {
        return readHead(headData, this.bs.duplicate());
    }

    /* JADX WARN: Multi-variable type inference failed */
    private <T> T[] readArrayImpl(T t, int i, boolean z2) {
        if (skipToTag(i)) {
            if (readHead().type == 9) {
                int read = read(0, 0, true);
                if (read >= 0) {
                    T[] tArr = (T[]) ((Object[]) Array.newInstance(t.getClass(), read));
                    for (int i2 = 0; i2 < read; i2++) {
                        tArr[i2] = (T) read(t, 0, true);
                    }
                    return tArr;
                }
                throw new JceDecodeException("size invalid: " + read);
            }
            throw new JceDecodeException("type mismatch.");
        }
        if (!z2) {
            return null;
        }
        throw new JceDecodeException("require field not exist.");
    }

    public static int readHead(HeadData headData, ByteBuffer byteBuffer) {
        byte b2 = byteBuffer.get();
        headData.type = (byte) (b2 & 15);
        int i = (b2 & 240) >> 4;
        headData.tag = i;
        if (i != 15) {
            return 1;
        }
        headData.tag = byteBuffer.get() & 255;
        return 2;
    }

    private void skip(int i) {
        ByteBuffer byteBuffer = this.bs;
        byteBuffer.position(byteBuffer.position() + i);
    }

    private void skipField() {
        skipField(readHead().type);
    }

    public JceStruct directRead(JceStruct jceStruct, int i, boolean z2) {
        if (skipToTag(i)) {
            try {
                JceStruct newInit = jceStruct.newInit();
                if (readHead().type == 10) {
                    newInit.readFrom(this);
                    skipToStructEnd();
                    return newInit;
                }
                throw new JceDecodeException("type mismatch.");
            } catch (Exception e) {
                throw new JceDecodeException(e.getMessage());
            }
        }
        if (!z2) {
            return null;
        }
        throw new JceDecodeException("require field not exist.");
    }

    public ByteBuffer getBs() {
        return this.bs;
    }

    public boolean read(boolean z2, int i, boolean z3) {
        return read((byte) 0, i, z3) != 0;
    }

    public <T> T[] readArray(T[] tArr, int i, boolean z2) {
        if (tArr != null && tArr.length != 0) {
            return (T[]) readArrayImpl(tArr[0], i, z2);
        }
        throw new JceDecodeException("unable to get type of key and value.");
    }

    public String readByteString(String str, int i, boolean z2) {
        if (skipToTag(i)) {
            byte b2 = readHead().type;
            if (b2 != 6) {
                if (b2 == 7) {
                    int i2 = this.bs.getInt();
                    if (i2 <= 104857600 && i2 >= 0 && i2 <= this.bs.capacity()) {
                        byte[] bArr = new byte[i2];
                        this.bs.get(bArr);
                        return HexUtil.bytes2HexStr(bArr);
                    }
                    throw new JceDecodeException("String too long: " + i2);
                }
                throw new JceDecodeException("type mismatch.");
            }
            int i3 = this.bs.get();
            if (i3 < 0) {
                i3 += 256;
            }
            byte[] bArr2 = new byte[i3];
            this.bs.get(bArr2);
            return HexUtil.bytes2HexStr(bArr2);
        }
        if (!z2) {
            return str;
        }
        throw new JceDecodeException("require field not exist.");
    }

    public List readList(int i, boolean z2) {
        ArrayList arrayList = new ArrayList();
        if (skipToTag(i)) {
            if (readHead().type == 9) {
                int read = read(0, 0, true);
                if (read >= 0) {
                    for (int i2 = 0; i2 < read; i2++) {
                        switch (readHead().type) {
                            case 0:
                                skip(1);
                                break;
                            case 1:
                                skip(2);
                                break;
                            case 2:
                                skip(4);
                                break;
                            case 3:
                                skip(8);
                                break;
                            case 4:
                                skip(4);
                                break;
                            case 5:
                                skip(8);
                                break;
                            case 6:
                                int i3 = this.bs.get();
                                if (i3 < 0) {
                                    i3 += 256;
                                }
                                skip(i3);
                                break;
                            case 7:
                                skip(this.bs.getInt());
                                break;
                            case 8:
                            case 9:
                                break;
                            case 10:
                                try {
                                    JceStruct jceStruct = (JceStruct) Class.forName(JceStruct.class.getName()).getConstructor(new Class[0]).newInstance(new Object[0]);
                                    jceStruct.readFrom(this);
                                    skipToStructEnd();
                                    arrayList.add(jceStruct);
                                    break;
                                } catch (Exception e) {
                                    e.printStackTrace();
                                    throw new JceDecodeException("type mismatch." + e);
                                }
                            case 11:
                            default:
                                throw new JceDecodeException("type mismatch.");
                            case 12:
                                arrayList.add(new Integer(0));
                                break;
                        }
                    }
                } else {
                    throw new JceDecodeException("size invalid: " + read);
                }
            } else {
                throw new JceDecodeException("type mismatch.");
            }
        } else if (z2) {
            throw new JceDecodeException("require field not exist.");
        }
        return arrayList;
    }

    public <K, V> HashMap<K, V> readMap(Map<K, V> map, int i, boolean z2) {
        return (HashMap) readMap(new HashMap(), map, i, z2);
    }

    public String readString(int i, boolean z2) {
        if (skipToTag(i)) {
            byte b2 = readHead().type;
            if (b2 != 6) {
                if (b2 == 7) {
                    int i2 = this.bs.getInt();
                    if (i2 <= 104857600 && i2 >= 0 && i2 <= this.bs.capacity()) {
                        byte[] bArr = new byte[i2];
                        this.bs.get(bArr);
                        try {
                            return new String(bArr, this.sServerEncoding);
                        } catch (UnsupportedEncodingException unused) {
                            return new String(bArr);
                        }
                    }
                    throw new JceDecodeException("String too long: " + i2);
                }
                throw new JceDecodeException("type mismatch.");
            }
            int i3 = this.bs.get();
            if (i3 < 0) {
                i3 += 256;
            }
            byte[] bArr2 = new byte[i3];
            this.bs.get(bArr2);
            try {
                return new String(bArr2, this.sServerEncoding);
            } catch (UnsupportedEncodingException unused2) {
                return new String(bArr2);
            }
        }
        if (!z2) {
            return null;
        }
        throw new JceDecodeException("require field not exist.");
    }

    public Map<String, String> readStringMap(int i, boolean z2) {
        HashMap hashMap = new HashMap();
        if (skipToTag(i)) {
            if (readHead().type == 8) {
                int read = read(0, 0, true);
                if (read >= 0) {
                    for (int i2 = 0; i2 < read; i2++) {
                        hashMap.put(readString(0, true), readString(1, true));
                    }
                } else {
                    throw new JceDecodeException("size invalid: " + read);
                }
            } else {
                throw new JceDecodeException("type mismatch.");
            }
        } else if (z2) {
            throw new JceDecodeException("require field not exist.");
        }
        return hashMap;
    }

    public int setServerEncoding(String str) {
        this.sServerEncoding = str;
        return 0;
    }

    public void skipToStructEnd() {
        HeadData headData = new HeadData();
        do {
            readHead(headData);
            skipField(headData.type);
        } while (headData.type != 11);
    }

    public boolean skipToTag(int i) {
        int i2;
        try {
            HeadData headData = new HeadData();
            while (true) {
                int peakHead = peakHead(headData);
                i2 = headData.tag;
                if (i <= i2 || headData.type == 11) {
                    break;
                }
                skip(peakHead);
                skipField(headData.type);
            }
            if (headData.type == 11 || i != i2) {
                return false;
            }
            return true;
        } catch (JceDecodeException | BufferUnderflowException unused) {
            return false;
        }
    }

    public void warp(byte[] bArr) {
        wrap(bArr);
    }

    public void wrap(byte[] bArr) {
        this.bs = ByteBuffer.wrap(bArr);
    }

    /* JADX WARN: Multi-variable type inference failed */
    private <K, V> Map<K, V> readMap(Map<K, V> map, Map<K, V> map2, int i, boolean z2) {
        if (map2 != null && !map2.isEmpty()) {
            Map.Entry<K, V> next = map2.entrySet().iterator().next();
            K key = next.getKey();
            V value = next.getValue();
            if (skipToTag(i)) {
                if (readHead().type == 8) {
                    int read = read(0, 0, true);
                    if (read < 0) {
                        throw new JceDecodeException("size invalid: " + read);
                    }
                    for (int i2 = 0; i2 < read; i2++) {
                        map.put((K) read(key, 0, true), (V) read(value, 1, true));
                    }
                } else {
                    throw new JceDecodeException("type mismatch.");
                }
            } else if (z2) {
                throw new JceDecodeException("require field not exist.");
            }
            return map;
        }
        return new HashMap();
    }

    public byte read(byte b2, int i, boolean z2) {
        if (!skipToTag(i)) {
            if (z2) {
                throw new JceDecodeException("require field not exist.");
            }
            return b2;
        }
        byte b3 = readHead().type;
        if (b3 == 0) {
            return this.bs.get();
        }
        if (b3 == 12) {
            return (byte) 0;
        }
        throw new JceDecodeException("type mismatch.");
    }

    public JceInputStream(ByteBuffer byteBuffer) {
        this.bs = byteBuffer;
    }

    public <T> List<T> readArray(List<T> list, int i, boolean z2) {
        if (list != null && !list.isEmpty()) {
            Object[] readArrayImpl = readArrayImpl(list.get(0), i, z2);
            if (readArrayImpl == null) {
                return null;
            }
            ArrayList arrayList = new ArrayList();
            for (Object obj : readArrayImpl) {
                arrayList.add(obj);
            }
            return arrayList;
        }
        return new ArrayList();
    }

    public void readHead(HeadData headData) {
        readHead(headData, this.bs);
    }

    public JceInputStream(byte[] bArr) {
        this.bs = ByteBuffer.wrap(bArr);
    }

    private void skipField(byte b2) {
        int i = 0;
        switch (b2) {
            case 0:
                skip(1);
                return;
            case 1:
                skip(2);
                return;
            case 2:
                skip(4);
                return;
            case 3:
                skip(8);
                return;
            case 4:
                skip(4);
                return;
            case 5:
                skip(8);
                return;
            case 6:
                int i2 = this.bs.get();
                if (i2 < 0) {
                    i2 += 256;
                }
                skip(i2);
                return;
            case 7:
                skip(this.bs.getInt());
                return;
            case 8:
                int read = read(0, 0, true);
                while (i < read * 2) {
                    skipField();
                    i++;
                }
                return;
            case 9:
                int read2 = read(0, 0, true);
                while (i < read2) {
                    skipField();
                    i++;
                }
                return;
            case 10:
                skipToStructEnd();
                return;
            case 11:
            case 12:
                return;
            case 13:
                HeadData b3 = readHead();
                if (b3.type == 0) {
                    skip(read(0, 0, true));
                    return;
                } else {
                    StringBuilder b4 = new StringBuilder("skipField with invalid type, type value: ").append(b2).append(", ");
                    b4.append((int) b3.type);
                    throw new JceDecodeException(b4.toString());
                }
            default:
                throw new JceDecodeException("invalid type.");
        }
    }

    public JceInputStream(byte[] bArr, int i) {
        ByteBuffer wrap = ByteBuffer.wrap(bArr);
        this.bs = wrap;
        wrap.position(i);
    }

    public short read(short s, int i, boolean z2) {
        if (!skipToTag(i)) {
            if (z2) {
                throw new JceDecodeException("require field not exist.");
            }
            return s;
        }
        byte b2 = readHead().type;
        if (b2 == 0) {
            return this.bs.get();
        }
        if (b2 == 1) {
            return this.bs.getShort();
        }
        if (b2 == 12) {
            return (short) 0;
        }
        throw new JceDecodeException("type mismatch.");
    }

    public int read(int i, int i2, boolean z2) {
        if (!skipToTag(i2)) {
            if (z2) {
                throw new JceDecodeException("require field not exist.");
            }
            return i;
        }
        byte b2 = readHead().type;
        if (b2 == 0) {
            return this.bs.get();
        }
        if (b2 == 1) {
            return this.bs.getShort();
        }
        if (b2 == 2) {
            return this.bs.getInt();
        }
        if (b2 == 12) {
            return 0;
        }
        throw new JceDecodeException("type mismatch.");
    }

    public long read(long j2, int i, boolean z2) {
        int i2;
        if (!skipToTag(i)) {
            if (z2) {
                throw new JceDecodeException("require field not exist.");
            }
            return j2;
        }
        byte b2 = readHead().type;
        if (b2 == 0) {
            i2 = this.bs.get();
        } else if (b2 == 1) {
            i2 = this.bs.getShort();
        } else {
            if (b2 != 2) {
                if (b2 == 3) {
                    return this.bs.getLong();
                }
                if (b2 == 12) {
                    return 0L;
                }
                throw new JceDecodeException("type mismatch.");
            }
            i2 = this.bs.getInt();
        }
        return i2;
    }

    public float read(float f, int i, boolean z2) {
        if (!skipToTag(i)) {
            if (z2) {
                throw new JceDecodeException("require field not exist.");
            }
            return f;
        }
        byte b2 = readHead().type;
        if (b2 == 4) {
            return this.bs.getFloat();
        }
        if (b2 == 12) {
            return 0.0f;
        }
        throw new JceDecodeException("type mismatch.");
    }

    public double read(double d, int i, boolean z2) {
        if (!skipToTag(i)) {
            if (z2) {
                throw new JceDecodeException("require field not exist.");
            }
            return d;
        }
        byte b2 = readHead().type;
        if (b2 == 4) {
            return this.bs.getFloat();
        }
        if (b2 == 5) {
            return this.bs.getDouble();
        }
        if (b2 == 12) {
            return 0.0d;
        }
        throw new JceDecodeException("type mismatch.");
    }

    public String read(String str, int i, boolean z2) {
        String str2;
        if (!skipToTag(i)) {
            if (z2) {
                throw new JceDecodeException("require field not exist.");
            }
            return str;
        }
        byte b2 = readHead().type;
        if (b2 == 6) {
            int i2 = this.bs.get();
            if (i2 < 0) {
                i2 += 256;
            }
            byte[] bArr = new byte[i2];
            this.bs.get(bArr);
            try {
                str2 = new String(bArr, this.sServerEncoding);
            } catch (UnsupportedEncodingException unused) {
                str2 = new String(bArr);
            }
        } else if (b2 == 7) {
            int i3 = this.bs.getInt();
            if (i3 <= 104857600 && i3 >= 0 && i3 <= this.bs.capacity()) {
                byte[] bArr2 = new byte[i3];
                this.bs.get(bArr2);
                try {
                    str2 = new String(bArr2, this.sServerEncoding);
                } catch (UnsupportedEncodingException unused2) {
                    str2 = new String(bArr2);
                }
            } else {
                throw new JceDecodeException("String too long: " + i3);
            }
        } else {
            throw new JceDecodeException("type mismatch.");
        }
        return str2;
    }

    public String[] read(String[] strArr, int i, boolean z2) {
        return (String[]) readArray(strArr, i, z2);
    }

    public boolean[] read(boolean[] zArr, int i, boolean z2) {
        if (!skipToTag(i)) {
            if (z2) {
                throw new JceDecodeException("require field not exist.");
            }
            return null;
        }
        if (readHead().type == 9) {
            int read = read(0, 0, true);
            if (read >= 0) {
                boolean[] zArr2 = new boolean[read];
                for (int i2 = 0; i2 < read; i2++) {
                    zArr2[i2] = read(zArr2[0], 0, true);
                }
                return zArr2;
            }
            throw new JceDecodeException("size invalid: " + read);
        }
        throw new JceDecodeException("type mismatch.");
    }

    public byte[] read(byte[] bArr, int i, boolean z2) {
        if (!skipToTag(i)) {
            if (z2) {
                throw new JceDecodeException("require field not exist.");
            }
            return null;
        }
        HeadData b2 = readHead();
        byte b3 = b2.type;
        if (b3 == 9) {
            int read = read(0, 0, true);
            if (read >= 0 && read <= this.bs.capacity()) {
                byte[] bArr2 = new byte[read];
                for (int i2 = 0; i2 < read; i2++) {
                    bArr2[i2] = read(bArr2[0], 0, true);
                }
                return bArr2;
            }
            throw new JceDecodeException("size invalid: " + read);
        }
        if (b3 == 13) {
            HeadData b4 = readHead();
            if (b4.type == 0) {
                int read2 = read(0, 0, true);
                if (read2 >= 0 && read2 <= this.bs.capacity()) {
                    byte[] bArr3 = new byte[read2];
                    this.bs.get(bArr3);
                    return bArr3;
                }
                StringBuilder b5 = new StringBuilder("invalid size, tag: ").append(i).append(", type: ");
                b5.append((int) b2.type);
                b5.append(", ");
                b5.append((int) b4.type);
                b5.append(", size: ");
                b5.append(read2);
                throw new JceDecodeException(b5.toString());
            }
            StringBuilder b6 = new StringBuilder("type mismatch, tag: ").append(i).append(", type: ");
            b6.append((int) b2.type);
            b6.append(", ");
            b6.append((int) b4.type);
            throw new JceDecodeException(b6.toString());
        }
        throw new JceDecodeException("type mismatch.");
    }

    public short[] read(short[] sArr, int i, boolean z2) {
        if (!skipToTag(i)) {
            if (z2) {
                throw new JceDecodeException("require field not exist.");
            }
            return null;
        }
        if (readHead().type == 9) {
            int read = read(0, 0, true);
            if (read >= 0) {
                short[] sArr2 = new short[read];
                for (int i2 = 0; i2 < read; i2++) {
                    sArr2[i2] = read(sArr2[0], 0, true);
                }
                return sArr2;
            }
            throw new JceDecodeException("size invalid: " + read);
        }
        throw new JceDecodeException("type mismatch.");
    }

    public int[] read(int[] iArr, int i, boolean z2) {
        if (!skipToTag(i)) {
            if (z2) {
                throw new JceDecodeException("require field not exist.");
            }
            return null;
        }
        if (readHead().type == 9) {
            int read = read(0, 0, true);
            if (read >= 0) {
                int[] iArr2 = new int[read];
                for (int i2 = 0; i2 < read; i2++) {
                    iArr2[i2] = read(iArr2[0], 0, true);
                }
                return iArr2;
            }
            throw new JceDecodeException("size invalid: " + read);
        }
        throw new JceDecodeException("type mismatch.");
    }

    public long[] read(long[] jArr, int i, boolean z2) {
        if (!skipToTag(i)) {
            if (z2) {
                throw new JceDecodeException("require field not exist.");
            }
            return null;
        }
        if (readHead().type == 9) {
            int read = read(0, 0, true);
            if (read >= 0) {
                long[] jArr2 = new long[read];
                for (int i2 = 0; i2 < read; i2++) {
                    jArr2[i2] = read(jArr2[0], 0, true);
                }
                return jArr2;
            }
            throw new JceDecodeException("size invalid: " + read);
        }
        throw new JceDecodeException("type mismatch.");
    }

    public float[] read(float[] fArr, int i, boolean z2) {
        if (!skipToTag(i)) {
            if (z2) {
                throw new JceDecodeException("require field not exist.");
            }
            return null;
        }
        if (readHead().type == 9) {
            int read = read(0, 0, true);
            if (read >= 0) {
                float[] fArr2 = new float[read];
                for (int i2 = 0; i2 < read; i2++) {
                    fArr2[i2] = read(fArr2[0], 0, true);
                }
                return fArr2;
            }
            throw new JceDecodeException("size invalid: " + read);
        }
        throw new JceDecodeException("type mismatch.");
    }

    public double[] read(double[] dArr, int i, boolean z2) {
        if (!skipToTag(i)) {
            if (z2) {
                throw new JceDecodeException("require field not exist.");
            }
            return null;
        }
        if (readHead().type == 9) {
            int read = read(0, 0, true);
            if (read >= 0) {
                double[] dArr2 = new double[read];
                for (int i2 = 0; i2 < read; i2++) {
                    dArr2[i2] = read(dArr2[0], 0, true);
                }
                return dArr2;
            }
            throw new JceDecodeException("size invalid: " + read);
        }
        throw new JceDecodeException("type mismatch.");
    }

    public JceStruct read(JceStruct jceStruct, int i, boolean z2) {
        if (!skipToTag(i)) {
            if (z2) {
                throw new JceDecodeException("require field not exist.");
            }
            return null;
        }
        try {
            JceStruct jceStruct2 = (JceStruct) jceStruct.getClass().newInstance();
            if (readHead().type == 10) {
                jceStruct2.readFrom(this);
                skipToStructEnd();
                return jceStruct2;
            }
            throw new JceDecodeException("type mismatch.");
        } catch (Exception e) {
            throw new JceDecodeException(e.getMessage());
        }
    }

    public JceStruct[] read(JceStruct[] jceStructArr, int i, boolean z2) {
        return (JceStruct[]) readArray(jceStructArr, i, z2);
    }

    /* JADX WARN: Multi-variable type inference failed */
    public <T> Object read(T t, int i, boolean z2) {
        if (t instanceof Byte) {
            return Byte.valueOf(read((byte) 0, i, z2));
        }
        if (t instanceof Boolean) {
            return Boolean.valueOf(read(false, i, z2));
        }
        if (t instanceof Short) {
            return Short.valueOf(read((short) 0, i, z2));
        }
        if (t instanceof Integer) {
            return Integer.valueOf(read(0, i, z2));
        }
        if (t instanceof Long) {
            return Long.valueOf(read(0L, i, z2));
        }
        if (t instanceof Float) {
            return Float.valueOf(read(0.0f, i, z2));
        }
        if (t instanceof Double) {
            return Double.valueOf(read(0.0d, i, z2));
        }
        if (t instanceof String) {
            return readString(i, z2);
        }
        if (t instanceof Map) {
            return readMap((Map) t, i, z2);
        }
        if (t instanceof List) {
            return readArray((List) t, i, z2);
        }
        if (t instanceof JceStruct) {
            return read((JceStruct) t, i, z2);
        }
        if (t.getClass().isArray()) {
            if (!(t instanceof byte[]) && !(t instanceof Byte[])) {
                if (t instanceof boolean[]) {
                    return read((boolean[]) null, i, z2);
                }
                if (t instanceof short[]) {
                    return read((short[]) null, i, z2);
                }
                if (t instanceof int[]) {
                    return read((int[]) null, i, z2);
                }
                if (t instanceof long[]) {
                    return read((long[]) null, i, z2);
                }
                if (t instanceof float[]) {
                    return read((float[]) null, i, z2);
                }
                if (t instanceof double[]) {
                    return read((double[]) null, i, z2);
                }
                return readArray((Object[]) t, i, z2);
            }
            return read((byte[]) null, i, z2);
        }
        throw new JceDecodeException("read object error: unsupport type.");
    }
}
