package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;
import java.util.ArrayList;
import java.util.Collection;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class ReqHead extends JceStruct {
    static Terminal cache_oldTerminal;
    static FlexPushInfo cache_pushInfo;
    static TuringSDKData cache_turingSDKData;
    public short areacode;
    public String botUid;
    public byte caller;
    public long clientTimestamp;
    public byte cmdFlag;
    public int cmdId;
    public byte encryptWithPack;
    public ArrayList<ReqHeadExternal> externalList;
    public String funId;
    public byte isForeground;
    public String moloDeviceId;
    public Net net;
    public long newAreaCode;
    public Terminal oldTerminal;
    public String phoneGuid;
    public FlexPushInfo pushInfo;
    public String qImei;
    public String qua;
    public int requestId;
    public byte sceneFlag;
    public int souceOrg;
    public long svrTimestamp;
    public Terminal terminal;
    public TerminalExtra terminalExtra;
    public Ticket ticket;
    public TuringSDKData turingData;
    public long uin;
    public String via;
    static Ticket cache_ticket = new Ticket();
    static Net cache_net = new Net();
    static Terminal cache_terminal = new Terminal();
    static TerminalExtra cache_terminalExtra = new TerminalExtra();
    static ArrayList<ReqHeadExternal> cache_externalList = new ArrayList<>();

    static {
        cache_externalList.add(new ReqHeadExternal());
        cache_pushInfo = new FlexPushInfo();
        cache_oldTerminal = new Terminal();
        cache_turingSDKData = new TuringSDKData();
    }

    public ReqHead() {
        this.requestId = 0;
        this.cmdId = 0;
        this.phoneGuid = "";
        this.ticket = null;
        this.qua = "";
        this.net = null;
        this.areacode = (short) 0;
        this.terminal = null;
        this.uin = 0L;
        this.moloDeviceId = "";
        this.encryptWithPack = (byte) 0;
        this.isForeground = (byte) 0;
        this.caller = (byte) 0;
        this.qImei = "";
        this.via = "";
        this.souceOrg = 0;
        this.terminalExtra = null;
        this.svrTimestamp = 0L;
        this.clientTimestamp = 0L;
        this.cmdFlag = (byte) 0;
        this.externalList = null;
        this.newAreaCode = 0L;
        this.sceneFlag = (byte) 0;
        this.funId = "";
        this.pushInfo = null;
        this.botUid = "";
        this.oldTerminal = null;
        this.turingData = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.requestId = jceInputStream.read(this.requestId, 0, true);
        this.cmdId = jceInputStream.read(this.cmdId, 1, true);
        this.phoneGuid = jceInputStream.readString(2, true);
        this.ticket = (Ticket) jceInputStream.read((JceStruct) cache_ticket, 3, false);
        this.qua = jceInputStream.readString(4, true);
        this.net = (Net) jceInputStream.read((JceStruct) cache_net, 5, true);
        this.areacode = jceInputStream.read(this.areacode, 6, true);
        this.terminal = (Terminal) jceInputStream.read((JceStruct) cache_terminal, 7, false);
        this.uin = jceInputStream.read(this.uin, 8, false);
        this.moloDeviceId = jceInputStream.readString(9, false);
        this.encryptWithPack = jceInputStream.read(this.encryptWithPack, 10, false);
        this.isForeground = jceInputStream.read(this.isForeground, 11, false);
        this.caller = jceInputStream.read(this.caller, 12, false);
        this.qImei = jceInputStream.readString(13, false);
        this.via = jceInputStream.readString(14, false);
        this.souceOrg = jceInputStream.read(this.souceOrg, 15, false);
        this.terminalExtra = (TerminalExtra) jceInputStream.read((JceStruct) cache_terminalExtra, 16, false);
        this.svrTimestamp = jceInputStream.read(this.svrTimestamp, 17, false);
        this.clientTimestamp = jceInputStream.read(this.clientTimestamp, 18, false);
        this.cmdFlag = jceInputStream.read(this.cmdFlag, 19, false);
        this.externalList = (ArrayList) jceInputStream.read(cache_externalList, 20, false);
        this.newAreaCode = jceInputStream.read(this.newAreaCode, 21, false);
        this.sceneFlag = jceInputStream.read(this.sceneFlag, 22, false);
        this.funId = jceInputStream.readString(23, false);
        this.pushInfo = (FlexPushInfo) jceInputStream.read((JceStruct) cache_pushInfo, 24, false);
        this.botUid = jceInputStream.readString(25, false);
        this.oldTerminal = (Terminal) jceInputStream.read((JceStruct) cache_oldTerminal, 26, false);
        this.turingData = (TuringSDKData) jceInputStream.read((JceStruct) cache_turingSDKData, 27, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.requestId, 0);
        jceOutputStream.write(this.cmdId, 1);
        jceOutputStream.write(this.phoneGuid, 2);
        Ticket ticket = this.ticket;
        if (ticket != null) {
            jceOutputStream.write((JceStruct) ticket, 3);
        }
        jceOutputStream.write(this.qua, 4);
        jceOutputStream.write((JceStruct) this.net, 5);
        jceOutputStream.write(this.areacode, 6);
        Terminal terminal = this.terminal;
        if (terminal != null) {
            jceOutputStream.write((JceStruct) terminal, 7);
        }
        jceOutputStream.write(this.uin, 8);
        String str = this.moloDeviceId;
        if (str != null) {
            jceOutputStream.write(str, 9);
        }
        jceOutputStream.write(this.encryptWithPack, 10);
        jceOutputStream.write(this.isForeground, 11);
        jceOutputStream.write(this.caller, 12);
        String str2 = this.qImei;
        if (str2 != null) {
            jceOutputStream.write(str2, 13);
        }
        String str3 = this.via;
        if (str3 != null) {
            jceOutputStream.write(str3, 14);
        }
        jceOutputStream.write(this.souceOrg, 15);
        TerminalExtra terminalExtra = this.terminalExtra;
        if (terminalExtra != null) {
            jceOutputStream.write((JceStruct) terminalExtra, 16);
        }
        jceOutputStream.write(this.svrTimestamp, 17);
        jceOutputStream.write(this.clientTimestamp, 18);
        jceOutputStream.write(this.cmdFlag, 19);
        ArrayList<ReqHeadExternal> arrayList = this.externalList;
        if (arrayList != null) {
            jceOutputStream.write((Collection) arrayList, 20);
        }
        jceOutputStream.write(this.newAreaCode, 21);
        jceOutputStream.write(this.sceneFlag, 22);
        String str4 = this.funId;
        if (str4 != null) {
            jceOutputStream.write(str4, 23);
        }
        FlexPushInfo flexPushInfo = this.pushInfo;
        if (flexPushInfo != null) {
            jceOutputStream.write((JceStruct) flexPushInfo, 24);
        }
        String str5 = this.botUid;
        if (str5 != null) {
            jceOutputStream.write(str5, 25);
        }
        Terminal terminal2 = this.oldTerminal;
        if (terminal2 != null) {
            jceOutputStream.write((JceStruct) terminal2, 26);
        }
        TuringSDKData turingSDKData = this.turingData;
        if (turingSDKData != null) {
            jceOutputStream.write((JceStruct) turingSDKData, 27);
        }
    }

    public ReqHead(int i, int i2, String str, Ticket ticket, String str2, Net net, short s, Terminal terminal, long j2, String str3, byte b2, byte b3, byte b4, String str4, String str5, int i3, TerminalExtra terminalExtra, long j3, long j4, byte b5, ArrayList<ReqHeadExternal> arrayList, long j5, byte b6, String str6, FlexPushInfo flexPushInfo, String str7, Terminal terminal2, TuringSDKData turingSDKData) {
        this.requestId = i;
        this.cmdId = i2;
        this.phoneGuid = str;
        this.ticket = ticket;
        this.qua = str2;
        this.net = net;
        this.areacode = s;
        this.terminal = terminal;
        this.uin = j2;
        this.moloDeviceId = str3;
        this.encryptWithPack = b2;
        this.isForeground = b3;
        this.caller = b4;
        this.qImei = str4;
        this.via = str5;
        this.souceOrg = i3;
        this.terminalExtra = terminalExtra;
        this.svrTimestamp = j3;
        this.clientTimestamp = j4;
        this.cmdFlag = b5;
        this.externalList = arrayList;
        this.newAreaCode = j5;
        this.sceneFlag = b6;
        this.funId = str6;
        this.pushInfo = flexPushInfo;
        this.botUid = str7;
        this.oldTerminal = terminal2;
        this.turingData = turingSDKData;
    }
}
