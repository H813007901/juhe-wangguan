package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class AuthRequest extends JceStruct {
    static byte[] cache_certData;
    static ArrayList<FileItem> cache_keyFileList = new ArrayList<>();
    static Map<String, SimulatorCheck> cache_simulatorCheckMap;
    public byte[] certData;
    public ArrayList<FileItem> keyFileList;
    public String pkgName;
    public int randNum;
    public Map<String, SimulatorCheck> simulatorCheckMap;
    public int simulatorCheckResult;
    public int versionCode;

    static {
        cache_certData = new byte[]{0};
        cache_keyFileList.add(new FileItem());
        cache_simulatorCheckMap = new HashMap();
        cache_simulatorCheckMap.put("", new SimulatorCheck());
    }

    public AuthRequest() {
        this.certData = null;
        this.randNum = 0;
        this.pkgName = "";
        this.versionCode = 0;
        this.keyFileList = null;
        this.simulatorCheckMap = null;
        this.simulatorCheckResult = 0;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.certData = jceInputStream.read(cache_certData, 0, true);
        this.randNum = jceInputStream.read(this.randNum, 1, false);
        this.pkgName = jceInputStream.readString(2, false);
        this.versionCode = jceInputStream.read(this.versionCode, 3, false);
        this.keyFileList = (ArrayList) jceInputStream.read(cache_keyFileList, 4, false);
        this.simulatorCheckMap = (Map) jceInputStream.read(cache_simulatorCheckMap, 5, false);
        this.simulatorCheckResult = jceInputStream.read(this.simulatorCheckResult, 6, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.certData, 0);
        jceOutputStream.write(this.randNum, 1);
        String str = this.pkgName;
        if (str != null) {
            jceOutputStream.write(str, 2);
        }
        jceOutputStream.write(this.versionCode, 3);
        ArrayList<FileItem> arrayList = this.keyFileList;
        if (arrayList != null) {
            jceOutputStream.write((Collection) arrayList, 4);
        }
        Map<String, SimulatorCheck> map = this.simulatorCheckMap;
        if (map != null) {
            jceOutputStream.write((Map) map, 5);
        }
        jceOutputStream.write(this.simulatorCheckResult, 6);
    }

    public AuthRequest(byte[] bArr, int i, String str, int i2, ArrayList<FileItem> arrayList, Map<String, SimulatorCheck> map, int i3) {
        this.certData = bArr;
        this.randNum = i;
        this.pkgName = str;
        this.versionCode = i2;
        this.keyFileList = arrayList;
        this.simulatorCheckMap = map;
        this.simulatorCheckResult = i3;
    }
}
