package com.samp.mobile.launcher.searchrp;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Native SA-MP / open.mp UDP Query engine ('i' info, 'p' ping, 'r' rules).
 * Detects online status, player counts, real latency, IC worldtime, and server password lock.
 */
public final class SrpUdpQuery {

    public static class ServerInfo {
        public boolean online = false;
        public boolean passworded = false;
        public int players = 0;
        public int maxPlayers = 0;
        public int pingMs = -1;
        public String hostname = "SEARCH ROLEPLAY";
        public String gamemode = "Roleplay";
        public String language = "-";
        public String mapName = "San Andreas";
        public String worldTime = "";
        public String serverVersion = "open.mp";
        public Map<String, String> rules = new HashMap<>();
    }

    public static class PasswordProbeResult {
        public final boolean reachable;
        public final boolean passworded;

        public PasswordProbeResult(boolean reachable, boolean passworded) {
            this.reachable = reachable;
            this.passworded = passworded;
        }
    }

    private static byte[] buildPacket(byte[] ipBytes, int port, byte opcode, byte[] extra) {
        int extraLen = extra == null ? 0 : extra.length;
        byte[] pkt = new byte[11 + extraLen];
        pkt[0] = 'S';
        pkt[1] = 'A';
        pkt[2] = 'M';
        pkt[3] = 'P';
        pkt[4] = ipBytes[0];
        pkt[5] = ipBytes[1];
        pkt[6] = ipBytes[2];
        pkt[7] = ipBytes[3];
        pkt[8] = (byte) (port & 0xFF);
        pkt[9] = (byte) ((port >> 8) & 0xFF);
        pkt[10] = opcode;
        if (extraLen > 0) {
            System.arraycopy(extra, 0, pkt, 11, extraLen);
        }
        return pkt;
    }

    private static class RawUdpResp {
        byte[] data;
        int len;
        int rttMs;
    }

    private static RawUdpResp sendUdp(InetAddress addr, byte[] ipBytes, int port, byte opcode, byte[] extra, int timeoutMs) {
        DatagramSocket sock = null;
        try {
            sock = new DatagramSocket();
            sock.setSoTimeout(timeoutMs);
            byte[] payload = buildPacket(ipBytes, port, opcode, extra);
            DatagramPacket out = new DatagramPacket(payload, payload.length, addr, port);
            long t0 = System.nanoTime();
            sock.send(out);

            byte[] buf = new byte[4096];
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            sock.receive(in);
            int rtt = (int) Math.max(1L, Math.round((System.nanoTime() - t0) / 1_000_000.0));

            if (in.getLength() >= 11 && buf[0] == 'S' && buf[1] == 'A' && buf[2] == 'M' && buf[3] == 'P' && buf[10] == opcode) {
                RawUdpResp r = new RawUdpResp();
                r.data = buf;
                r.len = in.getLength();
                r.rttMs = rtt;
                return r;
            }
        } catch (Exception ignored) {
        } finally {
            if (sock != null) {
                try {
                    sock.close();
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    public static PasswordProbeResult queryPasswordFlag(String host, int port, int timeoutMs) {
        try {
            InetAddress addr = InetAddress.getByName(host);
            byte[] ipBytes = addr.getAddress();
            if (ipBytes == null || ipBytes.length != 4) {
                return new PasswordProbeResult(false, false);
            }
            RawUdpResp resp = sendUdp(addr, ipBytes, port, (byte) 'i', null, timeoutMs);
            if (resp != null && resp.len >= 12) {
                return new PasswordProbeResult(true, (resp.data[11] & 0xFF) != 0);
            }
        } catch (Exception ignored) {
        }
        return new PasswordProbeResult(false, false);
    }

    public static ServerInfo queryServer(String host, int port, int timeoutMs) {
        ServerInfo info = new ServerInfo();
        try {
            InetAddress addr = InetAddress.getByName(host);
            byte[] ipBytes = addr.getAddress();
            if (ipBytes == null || ipBytes.length != 4) return info;

            // 1. Info opcode ('i')
            RawUdpResp iResp = sendUdp(addr, ipBytes, port, (byte) 'i', null, timeoutMs);
            if (iResp == null || iResp.len < 20) return info;

            ByteBuffer bb = ByteBuffer.wrap(iResp.data, 0, iResp.len).order(ByteOrder.LITTLE_ENDIAN);
            bb.position(11);
            info.passworded = (bb.get() & 0xFF) != 0;
            info.players = bb.getShort() & 0xFFFF;
            info.maxPlayers = bb.getShort() & 0xFFFF;

            info.hostname = readLenString32(bb, iResp.len, "SEARCH ROLEPLAY");
            info.gamemode = readLenString32(bb, iResp.len, "Roleplay");
            info.language = readLenString32(bb, iResp.len, "-");
            info.online = true;
            info.pingMs = iResp.rttMs;

            // 2. Ping opcode ('p') for accurate network RTT
            byte[] pingExtra = new byte[4];
            long now = System.currentTimeMillis();
            pingExtra[0] = (byte) (now & 0xFF);
            pingExtra[1] = (byte) ((now >> 8) & 0xFF);
            pingExtra[2] = (byte) ((now >> 16) & 0xFF);
            pingExtra[3] = (byte) ((now >> 24) & 0xFF);
            RawUdpResp pResp = sendUdp(addr, ipBytes, port, (byte) 'p', pingExtra, 1400);
            if (pResp != null) {
                info.pingMs = pResp.rttMs;
            }

            // 3. Rules opcode ('r') for worldtime, mapname, version
            RawUdpResp rResp = sendUdp(addr, ipBytes, port, (byte) 'r', null, 1500);
            if (rResp != null && rResp.len >= 13) {
                ByteBuffer rb = ByteBuffer.wrap(rResp.data, 0, rResp.len).order(ByteOrder.LITTLE_ENDIAN);
                rb.position(11);
                int count = rb.getShort() & 0xFFFF;
                for (int i = 0; i < count; i++) {
                    if (rb.position() >= rResp.len) break;
                    String rk = readLenString8(rb, rResp.len);
                    if (rb.position() >= rResp.len) break;
                    String rv = readLenString8(rb, rResp.len);
                    if (!rk.isEmpty()) info.rules.put(rk, rv);
                }
                if (info.rules.containsKey("worldtime")) info.worldTime = info.rules.get("worldtime");
                if (info.rules.containsKey("mapname")) info.mapName = info.rules.get("mapname");
                if (info.rules.containsKey("version")) info.serverVersion = info.rules.get("version");
            }
        } catch (Exception ignored) {
        }
        return info;
    }

    private static String readLenString32(ByteBuffer bb, int maxLen, String fallback) {
        if (bb.position() + 4 > maxLen) return fallback;
        int slen = bb.getInt();
        if (slen < 0 || slen > 512 || bb.position() + slen > maxLen) return fallback;
        byte[] b = new byte[slen];
        bb.get(b);
        String s = new String(b, StandardCharsets.ISO_8859_1).trim();
        return s.isEmpty() ? fallback : s;
    }

    private static String readLenString8(ByteBuffer bb, int maxLen) {
        if (bb.position() + 1 > maxLen) return "";
        int slen = bb.get() & 0xFF;
        if (bb.position() + slen > maxLen) return "";
        byte[] b = new byte[slen];
        bb.get(b);
        return new String(b, StandardCharsets.ISO_8859_1).trim();
    }
}
