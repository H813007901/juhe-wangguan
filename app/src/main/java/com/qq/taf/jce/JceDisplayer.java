package com.qq.taf.jce;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class JceDisplayer {
    private int _level;
    private StringBuilder sb;

    public JceDisplayer(StringBuilder sb, int i) {
        this.sb = sb;
        this._level = i;
    }

    private void ps(String str) {
        for (int i = 0; i < this._level; i++) {
            this.sb.append('\t');
        }
        if (str != null) {
            StringBuilder sb = this.sb;
            sb.append(str);
            sb.append(": ");
        }
    }

    public JceDisplayer display(boolean z2, String str) {
        ps(str);
        StringBuilder sb = this.sb;
        sb.append(z2 ? 'T' : 'F');
        sb.append('\n');
        return this;
    }

    public JceDisplayer displaySimple(boolean z2, boolean z3) {
        this.sb.append(z2 ? 'T' : 'F');
        if (z3) {
            this.sb.append(",");
        }
        return this;
    }

    public JceDisplayer display(byte b2, String str) {
        ps(str);
        StringBuilder sb = this.sb;
        sb.append((int) b2);
        sb.append('\n');
        return this;
    }

    public JceDisplayer displaySimple(byte b2, boolean z2) {
        this.sb.append((int) b2);
        if (z2) {
            this.sb.append(",");
        }
        return this;
    }

    public JceDisplayer(StringBuilder sb) {
        this._level = 0;
        this.sb = sb;
    }

    public JceDisplayer display(char c2, String str) {
        ps(str);
        StringBuilder sb = this.sb;
        sb.append(c2);
        sb.append('\n');
        return this;
    }

    public JceDisplayer displaySimple(char c2, boolean z2) {
        this.sb.append(c2);
        if (z2) {
            this.sb.append(",");
        }
        return this;
    }

    public JceDisplayer display(short s, String str) {
        ps(str);
        StringBuilder sb = this.sb;
        sb.append((int) s);
        sb.append('\n');
        return this;
    }

    public JceDisplayer displaySimple(short s, boolean z2) {
        this.sb.append((int) s);
        if (z2) {
            this.sb.append(",");
        }
        return this;
    }

    public JceDisplayer display(int i, String str) {
        ps(str);
        StringBuilder sb = this.sb;
        sb.append(i);
        sb.append('\n');
        return this;
    }

    public JceDisplayer displaySimple(int i, boolean z2) {
        this.sb.append(i);
        if (z2) {
            this.sb.append(",");
        }
        return this;
    }

    public JceDisplayer display(long j2, String str) {
        ps(str);
        StringBuilder sb = this.sb;
        sb.append(j2);
        sb.append('\n');
        return this;
    }

    public JceDisplayer displaySimple(long j2, boolean z2) {
        this.sb.append(j2);
        if (z2) {
            this.sb.append(",");
        }
        return this;
    }

    public JceDisplayer display(float f, String str) {
        ps(str);
        StringBuilder sb = this.sb;
        sb.append(f);
        sb.append('\n');
        return this;
    }

    public JceDisplayer displaySimple(float f, boolean z2) {
        this.sb.append(f);
        if (z2) {
            this.sb.append(",");
        }
        return this;
    }

    public JceDisplayer display(double d, String str) {
        ps(str);
        StringBuilder sb = this.sb;
        sb.append(d);
        sb.append('\n');
        return this;
    }

    public JceDisplayer displaySimple(double d, boolean z2) {
        this.sb.append(d);
        if (z2) {
            this.sb.append(",");
        }
        return this;
    }

    public JceDisplayer display(String str, String str2) {
        ps(str2);
        if (str == null) {
            StringBuilder sb = this.sb;
            sb.append("null");
            sb.append('\n');
        } else {
            StringBuilder sb2 = this.sb;
            sb2.append(str);
            sb2.append('\n');
        }
        return this;
    }

    public JceDisplayer displaySimple(String str, boolean z2) {
        if (str == null) {
            this.sb.append("null");
        } else {
            this.sb.append(str);
        }
        if (z2) {
            this.sb.append(",");
        }
        return this;
    }

    public JceDisplayer display(byte[] bArr, String str) {
        ps(str);
        if (bArr == null) {
            StringBuilder sb = this.sb;
            sb.append("null");
            sb.append('\n');
            return this;
        }
        if (bArr.length == 0) {
            StringBuilder sb2 = this.sb;
            sb2.append(bArr.length);
            sb2.append(", []");
            sb2.append('\n');
            return this;
        }
        StringBuilder sb3 = this.sb;
        sb3.append(bArr.length);
        sb3.append(", [");
        sb3.append('\n');
        JceDisplayer jceDisplayer = new JceDisplayer(this.sb, this._level + 1);
        for (byte b2 : bArr) {
            jceDisplayer.display(b2, (String) null);
        }
        display("]", (String) null);
        return this;
    }

    public JceDisplayer displaySimple(byte[] bArr, boolean z2) {
        if (bArr == null || bArr.length == 0) {
            if (z2) {
                this.sb.append(",");
            }
            return this;
        }
        this.sb.append(HexUtil.bytes2HexStr(bArr));
        if (z2) {
            this.sb.append(",");
        }
        return this;
    }

    public JceDisplayer displaySimple(char[] cArr, boolean z2) {
        if (cArr == null || cArr.length == 0) {
            if (z2) {
                this.sb.append(",");
            }
            return this;
        }
        this.sb.append(new String(cArr));
        if (z2) {
            this.sb.append(",");
        }
        return this;
    }

    public JceDisplayer displaySimple(short[] sArr, boolean z2) {
        if (sArr != null && sArr.length != 0) {
            this.sb.append("[");
            JceDisplayer jceDisplayer = new JceDisplayer(this.sb, this._level + 1);
            for (int i = 0; i < sArr.length; i++) {
                short s = sArr[i];
                if (i != 0) {
                    this.sb.append(",");
                }
                jceDisplayer.displaySimple(s, false);
            }
            this.sb.append("]");
            if (z2) {
                this.sb.append(",");
            }
            return this;
        }
        this.sb.append("[]");
        if (z2) {
            this.sb.append(",");
        }
        return this;
    }

    public JceDisplayer display(char[] cArr, String str) {
        ps(str);
        if (cArr == null) {
            StringBuilder sb = this.sb;
            sb.append("null");
            sb.append('\n');
            return this;
        }
        if (cArr.length == 0) {
            StringBuilder sb2 = this.sb;
            sb2.append(cArr.length);
            sb2.append(", []");
            sb2.append('\n');
            return this;
        }
        StringBuilder sb3 = this.sb;
        sb3.append(cArr.length);
        sb3.append(", [");
        sb3.append('\n');
        JceDisplayer jceDisplayer = new JceDisplayer(this.sb, this._level + 1);
        for (char c2 : cArr) {
            jceDisplayer.display(c2, (String) null);
        }
        display("]", (String) null);
        return this;
    }

    public JceDisplayer display(short[] sArr, String str) {
        ps(str);
        if (sArr == null) {
            StringBuilder sb = this.sb;
            sb.append("null");
            sb.append('\n');
            return this;
        }
        if (sArr.length == 0) {
            StringBuilder sb2 = this.sb;
            sb2.append(sArr.length);
            sb2.append(", []");
            sb2.append('\n');
            return this;
        }
        StringBuilder sb3 = this.sb;
        sb3.append(sArr.length);
        sb3.append(", [");
        sb3.append('\n');
        JceDisplayer jceDisplayer = new JceDisplayer(this.sb, this._level + 1);
        for (short s : sArr) {
            jceDisplayer.display(s, (String) null);
        }
        display("]", (String) null);
        return this;
    }

    public JceDisplayer displaySimple(int[] iArr, boolean z2) {
        if (iArr != null && iArr.length != 0) {
            this.sb.append("[");
            JceDisplayer jceDisplayer = new JceDisplayer(this.sb, this._level + 1);
            for (int i = 0; i < iArr.length; i++) {
                int i2 = iArr[i];
                if (i != 0) {
                    this.sb.append(",");
                }
                jceDisplayer.displaySimple(i2, false);
            }
            this.sb.append("]");
            if (z2) {
                this.sb.append(",");
            }
            return this;
        }
        this.sb.append("[]");
        if (z2) {
            this.sb.append(",");
        }
        return this;
    }

    public JceDisplayer display(int[] iArr, String str) {
        ps(str);
        if (iArr == null) {
            StringBuilder sb = this.sb;
            sb.append("null");
            sb.append('\n');
            return this;
        }
        if (iArr.length == 0) {
            StringBuilder sb2 = this.sb;
            sb2.append(iArr.length);
            sb2.append(", []");
            sb2.append('\n');
            return this;
        }
        StringBuilder sb3 = this.sb;
        sb3.append(iArr.length);
        sb3.append(", [");
        sb3.append('\n');
        JceDisplayer jceDisplayer = new JceDisplayer(this.sb, this._level + 1);
        for (int i : iArr) {
            jceDisplayer.display(i, (String) null);
        }
        display("]", (String) null);
        return this;
    }

    public JceDisplayer displaySimple(long[] jArr, boolean z2) {
        if (jArr != null && jArr.length != 0) {
            this.sb.append("[");
            JceDisplayer jceDisplayer = new JceDisplayer(this.sb, this._level + 1);
            for (int i = 0; i < jArr.length; i++) {
                long j2 = jArr[i];
                if (i != 0) {
                    this.sb.append(",");
                }
                jceDisplayer.displaySimple(j2, false);
            }
            this.sb.append("]");
            if (z2) {
                this.sb.append(",");
            }
            return this;
        }
        this.sb.append("[]");
        if (z2) {
            this.sb.append(",");
        }
        return this;
    }

    public JceDisplayer display(long[] jArr, String str) {
        ps(str);
        if (jArr == null) {
            StringBuilder sb = this.sb;
            sb.append("null");
            sb.append('\n');
            return this;
        }
        if (jArr.length == 0) {
            StringBuilder sb2 = this.sb;
            sb2.append(jArr.length);
            sb2.append(", []");
            sb2.append('\n');
            return this;
        }
        StringBuilder sb3 = this.sb;
        sb3.append(jArr.length);
        sb3.append(", [");
        sb3.append('\n');
        JceDisplayer jceDisplayer = new JceDisplayer(this.sb, this._level + 1);
        for (long j2 : jArr) {
            jceDisplayer.display(j2, (String) null);
        }
        display("]", (String) null);
        return this;
    }

    public JceDisplayer displaySimple(float[] fArr, boolean z2) {
        if (fArr != null && fArr.length != 0) {
            this.sb.append("[");
            JceDisplayer jceDisplayer = new JceDisplayer(this.sb, this._level + 1);
            for (int i = 0; i < fArr.length; i++) {
                float f = fArr[i];
                if (i != 0) {
                    this.sb.append(",");
                }
                jceDisplayer.displaySimple(f, false);
            }
            this.sb.append("]");
            if (z2) {
                this.sb.append(",");
            }
            return this;
        }
        this.sb.append("[]");
        if (z2) {
            this.sb.append(",");
        }
        return this;
    }

    public JceDisplayer display(float[] fArr, String str) {
        ps(str);
        if (fArr == null) {
            StringBuilder sb = this.sb;
            sb.append("null");
            sb.append('\n');
            return this;
        }
        if (fArr.length == 0) {
            StringBuilder sb2 = this.sb;
            sb2.append(fArr.length);
            sb2.append(", []");
            sb2.append('\n');
            return this;
        }
        StringBuilder sb3 = this.sb;
        sb3.append(fArr.length);
        sb3.append(", [");
        sb3.append('\n');
        JceDisplayer jceDisplayer = new JceDisplayer(this.sb, this._level + 1);
        for (float f : fArr) {
            jceDisplayer.display(f, (String) null);
        }
        display("]", (String) null);
        return this;
    }

    public JceDisplayer displaySimple(double[] dArr, boolean z2) {
        if (dArr != null && dArr.length != 0) {
            this.sb.append("[");
            JceDisplayer jceDisplayer = new JceDisplayer(this.sb, this._level + 1);
            for (int i = 0; i < dArr.length; i++) {
                double d = dArr[i];
                if (i != 0) {
                    this.sb.append(",");
                }
                jceDisplayer.displaySimple(d, false);
            }
            this.sb.append("[");
            if (z2) {
                this.sb.append(",");
            }
            return this;
        }
        this.sb.append("[]");
        if (z2) {
            this.sb.append(",");
        }
        return this;
    }

    public JceDisplayer display(double[] dArr, String str) {
        ps(str);
        if (dArr == null) {
            StringBuilder sb = this.sb;
            sb.append("null");
            sb.append('\n');
            return this;
        }
        if (dArr.length == 0) {
            StringBuilder sb2 = this.sb;
            sb2.append(dArr.length);
            sb2.append(", []");
            sb2.append('\n');
            return this;
        }
        StringBuilder sb3 = this.sb;
        sb3.append(dArr.length);
        sb3.append(", [");
        sb3.append('\n');
        JceDisplayer jceDisplayer = new JceDisplayer(this.sb, this._level + 1);
        for (double d : dArr) {
            jceDisplayer.display(d, (String) null);
        }
        display("]", (String) null);
        return this;
    }

    public <K, V> JceDisplayer display(Map<K, V> map, String str) {
        ps(str);
        if (map == null) {
            StringBuilder sb = this.sb;
            sb.append("null");
            sb.append('\n');
            return this;
        }
        if (map.isEmpty()) {
            StringBuilder sb2 = this.sb;
            sb2.append(map.size());
            sb2.append(", {}");
            sb2.append('\n');
            return this;
        }
        StringBuilder sb3 = this.sb;
        sb3.append(map.size());
        sb3.append(", {");
        sb3.append('\n');
        JceDisplayer jceDisplayer = new JceDisplayer(this.sb, this._level + 1);
        JceDisplayer jceDisplayer2 = new JceDisplayer(this.sb, this._level + 2);
        for (Map.Entry<K, V> entry : map.entrySet()) {
            jceDisplayer.display('(', (String) null);
            jceDisplayer2.display(entry.getKey(), (String) null);
            jceDisplayer2.display(entry.getValue(), (String) null);
            jceDisplayer.display(')', (String) null);
        }
        display("}", (String) null);
        return this;
    }

    public <K, V> JceDisplayer displaySimple(Map<K, V> map, boolean z2) {
        if (map != null && !map.isEmpty()) {
            this.sb.append("{");
            JceDisplayer jceDisplayer = new JceDisplayer(this.sb, this._level + 2);
            boolean z3 = true;
            for (Map.Entry<K, V> entry : map.entrySet()) {
                if (!z3) {
                    this.sb.append(",");
                }
                jceDisplayer.displaySimple(entry.getKey(), true);
                jceDisplayer.displaySimple(entry.getValue(), false);
                z3 = false;
            }
            this.sb.append("}");
            if (z2) {
                this.sb.append(",");
            }
            return this;
        }
        this.sb.append("{}");
        if (z2) {
            this.sb.append(",");
        }
        return this;
    }

    public <T> JceDisplayer displaySimple(T[] tArr, boolean z2) {
        if (tArr != null && tArr.length != 0) {
            this.sb.append("[");
            JceDisplayer jceDisplayer = new JceDisplayer(this.sb, this._level + 1);
            for (int i = 0; i < tArr.length; i++) {
                T t = tArr[i];
                if (i != 0) {
                    this.sb.append(",");
                }
                jceDisplayer.displaySimple(t, false);
            }
            this.sb.append("]");
            if (z2) {
                this.sb.append(",");
            }
            return this;
        }
        this.sb.append("[]");
        if (z2) {
            this.sb.append(",");
        }
        return this;
    }

    public <T> JceDisplayer display(T[] tArr, String str) {
        ps(str);
        if (tArr == null) {
            StringBuilder sb = this.sb;
            sb.append("null");
            sb.append('\n');
            return this;
        }
        if (tArr.length == 0) {
            StringBuilder sb2 = this.sb;
            sb2.append(tArr.length);
            sb2.append(", []");
            sb2.append('\n');
            return this;
        }
        StringBuilder sb3 = this.sb;
        sb3.append(tArr.length);
        sb3.append(", [");
        sb3.append('\n');
        JceDisplayer jceDisplayer = new JceDisplayer(this.sb, this._level + 1);
        for (T t : tArr) {
            jceDisplayer.display(t, (String) null);
        }
        display("]", (String) null);
        return this;
    }

    /* JADX WARN: Multi-variable type inference failed */
    public <T> JceDisplayer display(Collection<T> collection, String str) {
        if (collection == null) {
            ps(str);
            StringBuilder sb = this.sb;
            sb.append("null");
            sb.append('\t');
            return this;
        }
        return display(collection.toArray(), str);
    }

    /* JADX WARN: Multi-variable type inference failed */
    public <T> JceDisplayer displaySimple(Collection<T> collection, boolean z2) {
        if (collection == null) {
            this.sb.append("[]");
            if (z2) {
                this.sb.append(",");
            }
            return this;
        }
        return displaySimple(collection.toArray(), z2);
    }

    /* JADX WARN: Multi-variable type inference failed */
    public <T> JceDisplayer display(T t, String str) {
        if (t == null) {
            StringBuilder sb = this.sb;
            sb.append("null");
            sb.append('\n');
        } else if (t instanceof Byte) {
            display(((Byte) t).byteValue(), str);
        } else if (t instanceof Boolean) {
            display(((Boolean) t).booleanValue(), str);
        } else if (t instanceof Short) {
            display(((Short) t).shortValue(), str);
        } else if (t instanceof Integer) {
            display(((Integer) t).intValue(), str);
        } else if (t instanceof Long) {
            display(((Long) t).longValue(), str);
        } else if (t instanceof Float) {
            display(((Float) t).floatValue(), str);
        } else if (t instanceof Double) {
            display(((Double) t).doubleValue(), str);
        } else if (t instanceof String) {
            display((String) t, str);
        } else if (t instanceof Map) {
            display((Map) t, str);
        } else if (t instanceof List) {
            display((Collection) t, str);
        } else if (t instanceof JceStruct) {
            display((JceStruct) t, str);
        } else if (t instanceof byte[]) {
            display((byte[]) t, str);
        } else if (t instanceof boolean[]) {
            display(t, str);
        } else if (t instanceof short[]) {
            display((short[]) t, str);
        } else if (t instanceof int[]) {
            display((int[]) t, str);
        } else if (t instanceof long[]) {
            display((long[]) t, str);
        } else if (t instanceof float[]) {
            display((float[]) t, str);
        } else if (t instanceof double[]) {
            display((double[]) t, str);
        } else if (t.getClass().isArray()) {
            display((Object[]) t, str);
        } else {
            throw new JceEncodeException("write object error: unsupport type.");
        }
        return this;
    }

    /* JADX WARN: Multi-variable type inference failed */
    public <T> JceDisplayer displaySimple(T t, boolean z2) {
        if (t == null) {
            StringBuilder sb = this.sb;
            sb.append("null");
            sb.append('\n');
        } else if (t instanceof Byte) {
            displaySimple(((Byte) t).byteValue(), z2);
        } else if (t instanceof Boolean) {
            displaySimple(((Boolean) t).booleanValue(), z2);
        } else if (t instanceof Short) {
            displaySimple(((Short) t).shortValue(), z2);
        } else if (t instanceof Integer) {
            displaySimple(((Integer) t).intValue(), z2);
        } else if (t instanceof Long) {
            displaySimple(((Long) t).longValue(), z2);
        } else if (t instanceof Float) {
            displaySimple(((Float) t).floatValue(), z2);
        } else if (t instanceof Double) {
            displaySimple(((Double) t).doubleValue(), z2);
        } else if (t instanceof String) {
            displaySimple((String) t, z2);
        } else if (t instanceof Map) {
            displaySimple((Map) t, z2);
        } else if (t instanceof List) {
            displaySimple((Collection) t, z2);
        } else if (t instanceof JceStruct) {
            displaySimple((JceStruct) t, z2);
        } else if (t instanceof byte[]) {
            displaySimple((byte[]) t, z2);
        } else if (t instanceof boolean[]) {
            displaySimple(t, z2);
        } else if (t instanceof short[]) {
            displaySimple((short[]) t, z2);
        } else if (t instanceof int[]) {
            displaySimple((int[]) t, z2);
        } else if (t instanceof long[]) {
            displaySimple((long[]) t, z2);
        } else if (t instanceof float[]) {
            displaySimple((float[]) t, z2);
        } else if (t instanceof double[]) {
            displaySimple((double[]) t, z2);
        } else if (t.getClass().isArray()) {
            displaySimple((Object[]) t, z2);
        } else {
            throw new JceEncodeException("write object error: unsupport type.");
        }
        return this;
    }

    public JceDisplayer display(JceStruct jceStruct, String str) {
        display("{", str);
        if (jceStruct == null) {
            StringBuilder sb = this.sb;
            sb.append('\t');
            sb.append("null");
        } else {
            jceStruct.display(this.sb, this._level + 1);
        }
        display("}", (String) null);
        return this;
    }

    public JceDisplayer displaySimple(JceStruct jceStruct, boolean z2) {
        this.sb.append("{");
        if (jceStruct == null) {
            StringBuilder sb = this.sb;
            sb.append('\t');
            sb.append("null");
        } else {
            jceStruct.displaySimple(this.sb, this._level + 1);
        }
        this.sb.append("}");
        if (z2) {
            this.sb.append(",");
        }
        return this;
    }
}
