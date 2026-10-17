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
public final class RspHead extends JceStruct {
    static Ticket cache_csTicket;
    static Map<String, byte[]> cache_extraData;
    static ArrayList<IPData> cache_ipData = new ArrayList<>();
    static Map<String, ArrayList<IPData>> cache_ipDataMap;
    static ArrayList<IPData> cache_ipKeepAliveMap;
    static FlexPushInfo cache_pushInfo;
    static Ticket cache_ticket;
    public short areacode;
    public String clientIp;
    public byte cmdFlag;
    public int cmdId;
    public Ticket csTicket;
    public int csTicketState;
    public int csTicketTTL;
    public byte encryptWithPack;
    public Map<String, byte[]> extraData;
    public String imei;
    public ArrayList<IPData> ipData;
    public Map<String, ArrayList<IPData>> ipDataMap;
    public ArrayList<IPData> ipKeepAliveMap;
    public String mac;
    public long newAreaCode;
    public String phoneGuid;
    public FlexPushInfo pushInfo;
    public String qimei36;
    public int requestId;
    public int ret;
    public long svrTimestamp;
    public Ticket ticket;

    static {
        cache_ipData.add(new IPData());
        cache_ticket = new Ticket();
        cache_csTicket = new Ticket();
        cache_ipDataMap = new HashMap();
        ArrayList<IPData> arrayList = new ArrayList<>();
        arrayList.add(new IPData());
        cache_ipDataMap.put("", arrayList);
        cache_pushInfo = new FlexPushInfo();
        cache_ipKeepAliveMap = new ArrayList<>();
        cache_ipKeepAliveMap.add(new IPData());
        HashMap hashMap = new HashMap();
        cache_extraData = hashMap;
        hashMap.put("", new byte[]{0});
    }

    public RspHead() {
        this.requestId = 0;
        this.cmdId = 0;
        this.areacode = (short) 0;
        this.ipData = null;
        this.ret = 0;
        this.phoneGuid = "";
        this.clientIp = "";
        this.encryptWithPack = (byte) 0;
        this.ticket = null;
        this.csTicket = null;
        this.svrTimestamp = 0L;
        this.csTicketState = 0;
        this.csTicketTTL = 0;
        this.cmdFlag = (byte) 0;
        this.newAreaCode = 0L;
        this.ipDataMap = null;
        this.imei = "";
        this.mac = "";
        this.pushInfo = null;
        this.ipKeepAliveMap = null;
        this.qimei36 = "";
        this.extraData = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.requestId = jceInputStream.read(this.requestId, 0, true);
        this.cmdId = jceInputStream.read(this.cmdId, 1, true);
        this.areacode = jceInputStream.read(this.areacode, 2, true);
        this.ipData = (ArrayList) jceInputStream.read(cache_ipData, 3, true);
        this.ret = jceInputStream.read(this.ret, 4, true);
        this.phoneGuid = jceInputStream.readString(5, false);
        this.clientIp = jceInputStream.readString(6, false);
        this.encryptWithPack = jceInputStream.read(this.encryptWithPack, 7, false);
        this.ticket = (Ticket) jceInputStream.read((JceStruct) cache_ticket, 8, false);
        this.csTicket = (Ticket) jceInputStream.read((JceStruct) cache_csTicket, 9, false);
        this.svrTimestamp = jceInputStream.read(this.svrTimestamp, 10, false);
        this.csTicketState = jceInputStream.read(this.csTicketState, 11, false);
        this.csTicketTTL = jceInputStream.read(this.csTicketTTL, 12, false);
        this.cmdFlag = jceInputStream.read(this.cmdFlag, 13, false);
        this.newAreaCode = jceInputStream.read(this.newAreaCode, 14, false);
        this.ipDataMap = (Map) jceInputStream.read(cache_ipDataMap, 15, false);
        this.imei = jceInputStream.readString(16, false);
        this.mac = jceInputStream.readString(17, false);
        this.pushInfo = (FlexPushInfo) jceInputStream.read((JceStruct) cache_pushInfo, 18, false);
        this.ipKeepAliveMap = (ArrayList) jceInputStream.read(cache_ipKeepAliveMap, 19, false);
        this.qimei36 = jceInputStream.readString(20, false);
        this.extraData = (Map) jceInputStream.read(cache_extraData, 21, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.requestId, 0);
        jceOutputStream.write(this.cmdId, 1);
        jceOutputStream.write(this.areacode, 2);
        jceOutputStream.write((Collection) this.ipData, 3);
        jceOutputStream.write(this.ret, 4);
        String str = this.phoneGuid;
        if (str != null) {
            jceOutputStream.write(str, 5);
        }
        String str2 = this.clientIp;
        if (str2 != null) {
            jceOutputStream.write(str2, 6);
        }
        jceOutputStream.write(this.encryptWithPack, 7);
        Ticket ticket = this.ticket;
        if (ticket != null) {
            jceOutputStream.write((JceStruct) ticket, 8);
        }
        Ticket ticket2 = this.csTicket;
        if (ticket2 != null) {
            jceOutputStream.write((JceStruct) ticket2, 9);
        }
        jceOutputStream.write(this.svrTimestamp, 10);
        jceOutputStream.write(this.csTicketState, 11);
        jceOutputStream.write(this.csTicketTTL, 12);
        jceOutputStream.write(this.cmdFlag, 13);
        jceOutputStream.write(this.newAreaCode, 14);
        Map<String, ArrayList<IPData>> map = this.ipDataMap;
        if (map != null) {
            jceOutputStream.write((Map) map, 15);
        }
        String str3 = this.imei;
        if (str3 != null) {
            jceOutputStream.write(str3, 16);
        }
        String str4 = this.mac;
        if (str4 != null) {
            jceOutputStream.write(str4, 17);
        }
        FlexPushInfo flexPushInfo = this.pushInfo;
        if (flexPushInfo != null) {
            jceOutputStream.write((JceStruct) flexPushInfo, 18);
        }
        ArrayList<IPData> arrayList = this.ipKeepAliveMap;
        if (arrayList != null) {
            jceOutputStream.write((Collection) arrayList, 19);
        }
        String str5 = this.qimei36;
        if (str5 != null) {
            jceOutputStream.write(str5, 20);
        }
        Map<String, byte[]> map2 = this.extraData;
        if (map2 != null) {
            jceOutputStream.write((Map) map2, 21);
        }
    }

    public RspHead(int i, int i2, short s, ArrayList<IPData> arrayList, int i3, String str, String str2, byte b2, Ticket ticket, Ticket ticket2, long j2, int i4, int i5, byte b3, long j3, Map<String, ArrayList<IPData>> map, String str3, String str4, FlexPushInfo flexPushInfo, ArrayList<IPData> arrayList2, String str5, Map<String, byte[]> map2) {
        this.requestId = i;
        this.cmdId = i2;
        this.areacode = s;
        this.ipData = arrayList;
        this.ret = i3;
        this.phoneGuid = str;
        this.clientIp = str2;
        this.encryptWithPack = b2;
        this.ticket = ticket;
        this.csTicket = ticket2;
        this.svrTimestamp = j2;
        this.csTicketState = i4;
        this.csTicketTTL = i5;
        this.cmdFlag = b3;
        this.newAreaCode = j3;
        this.ipDataMap = map;
        this.imei = str3;
        this.mac = str4;
        this.pushInfo = flexPushInfo;
        this.ipKeepAliveMap = arrayList2;
        this.qimei36 = str5;
        this.extraData = map2;
    }
}
