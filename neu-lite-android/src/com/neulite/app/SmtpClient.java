package com.neulite.app;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import android.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;

/** 极简 SMTP 发信：只支持文本邮件，用于校园邮箱客户端专用密码。 */
final class SmtpClient {
    private SmtpClient() {}

    static final class Attachment {
        final String name;
        final String type;
        final byte[] data;

        Attachment(String name, String type, byte[] data) {
            this.name = name;
            this.type = type;
            this.data = data;
        }
    }

    static void send(String from, String password, String to, String subject, String body) throws Exception {
        send(from, password, to, subject, body, null);
    }

    static void send(String from, String password, String to, String subject, String body,
                     List<Attachment> attachments) throws Exception {
        Exception last = null;
        Object[][] hosts = {
                {"mails.neu.edu.cn", 25, "starttls"},
                {"smtp.neu.edu.cn", 465, "ssl"},
                {"smtp.mails.neu.edu.cn", 465, "ssl"},
        };
        for (Object[] hp : hosts) {
            try {
                sendOne((String) hp[0], (Integer) hp[1], (String) hp[2], from, password, to, subject, body, attachments);
                return;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last == null ? new Exception("SMTP 连接失败") : last;
    }

    private static void sendOne(String host, int port, String mode, String from, String password,
                                String to, String subject, String body, List<Attachment> attachments) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 12000);
        socket.setSoTimeout(60000);
        if ("ssl".equals(mode)) socket = wrapTls(socket, host, port);

        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        OutputStream out = socket.getOutputStream();

        expect(in, 220);
        write(out, "EHLO neu-lite\r\n");
        String ehlo = read(in); // 250 multi-line
        if (!ehlo.startsWith("250")) throw new Exception("SMTP EHLO: " + ehlo);

        if ("starttls".equals(mode)) {
            if (!ehlo.toUpperCase(Locale.US).contains("STARTTLS")) throw new Exception("SMTP 不支持 STARTTLS");
            write(out, "STARTTLS\r\n");
            expect(in, 220);
            socket = wrapTls(socket, host, port);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = socket.getOutputStream();
            write(out, "EHLO neu-lite\r\n");
            ehlo = read(in);
            if (!ehlo.startsWith("250")) throw new Exception("SMTP EHLO(TLS): " + ehlo);
        }

        write(out, "AUTH LOGIN\r\n");
        expect(in, 334);
        write(out, Base64.encodeToString(from.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP) + "\r\n");
        expect(in, 334);
        write(out, Base64.encodeToString(password.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP) + "\r\n");
        expect(in, 235);

        write(out, "MAIL FROM:<" + from + ">\r\n");
        expect(in, 250);
        write(out, "RCPT TO:<" + to + ">\r\n");
        expect(in, 250);
        write(out, "DATA\r\n");
        expect(in, 354);

        String date = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.US).format(new Date());
        String head = "From: " + mime("日在NEU") + " <" + from + ">\r\n"
                + "To: <" + to + ">\r\n"
                + "Subject: " + mime(subject) + "\r\n"
                + "Date: " + date + "\r\n"
                + "MIME-Version: 1.0\r\n";
        boolean hasAttach = attachments != null && !attachments.isEmpty();
        String msg;
        if (!hasAttach) {
            msg = head + "Content-Type: text/plain; charset=UTF-8\r\n"
                    + "Content-Transfer-Encoding: base64\r\n\r\n"
                    + wrapBase64(Base64.encodeToString(body.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP))
                    + "\r\n.\r\n";
        } else {
            String boundary = "----neu-lite-" + System.currentTimeMillis();
            StringBuilder b = new StringBuilder(head)
                    .append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n\r\n")
                    .append("--").append(boundary).append("\r\n")
                    .append("Content-Type: text/plain; charset=UTF-8\r\n")
                    .append("Content-Transfer-Encoding: base64\r\n\r\n")
                    .append(wrapBase64(Base64.encodeToString(body.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP)))
                    .append("\r\n");
            for (Attachment a : attachments) {
                String name = mime(a.name == null ? "attachment" : a.name.replaceAll("[\\r\\n\"]", "_"));
                String type = a.type == null || a.type.isEmpty() ? "application/octet-stream" : a.type;
                b.append("--").append(boundary).append("\r\n")
                        .append("Content-Type: ").append(type).append("; name=\"").append(name).append("\"\r\n")
                        .append("Content-Transfer-Encoding: base64\r\n")
                        .append("Content-Disposition: attachment; filename=\"").append(name).append("\"\r\n\r\n")
                        .append(wrapBase64(Base64.encodeToString(a.data, Base64.NO_WRAP))).append("\r\n");
            }
            b.append("--").append(boundary).append("--\r\n");
            msg = b.toString();
        }
        write(out, msg);
        expect(in, 250);
        write(out, "QUIT\r\n");
        socket.close();
    }

    private static Socket wrapTls(Socket plain, String host, int port) throws Exception {
        SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket s = (SSLSocket) f.createSocket(plain, host, port, true);
        try {
            SSLParameters p = s.getSSLParameters();
            p.setServerNames(Collections.singletonList(new SNIHostName(host)));
            s.setSSLParameters(p);
        } catch (Exception ignored) {}
        s.startHandshake();
        verifyPeer(s, host);
        return s;
    }

    /** 校验证书链 + 主机名；mails.neu.edu.cn 使用 Coremail 云证书 *.icoremail.net。 */
    private static void verifyPeer(SSLSocket s, String host) throws Exception {
        SSLSession session = s.getSession();
        Certificate[] chain = session.getPeerCertificates();
        if (chain == null || chain.length == 0 || !(chain[0] instanceof X509Certificate)) {
            throw new Exception("SMTP TLS 无有效证书");
        }
        X509Certificate cert = (X509Certificate) chain[0];
        cert.checkValidity();
        if (hasDnsName(cert, host)) return;
        // 东北大学邮件由 Coremail 云托管，mails.neu.edu.cn 的证书 SAN 为 *.icoremail.net
        if ("mails.neu.edu.cn".equalsIgnoreCase(host)
                && (hasDnsName(cert, "*.icoremail.net") || hasDnsName(cert, "icoremail.net"))) return;
        throw new Exception("SMTP TLS 证书主机名不匹配: " + host);
    }

    private static boolean hasDnsName(X509Certificate cert, String host) {
        try {
            Collection<List<?>> names = cert.getSubjectAlternativeNames();
            if (names == null) return false;
            for (List<?> item : names) {
                if (item.size() < 2) continue;
                Object type = item.get(0);
                Object value = item.get(1);
                if (!(type instanceof Integer) || ((Integer) type) != 2 || !(value instanceof String)) continue;
                if (dnsMatch(host, (String) value)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean dnsMatch(String host, String pattern) {
        host = host.toLowerCase(Locale.US);
        pattern = pattern.toLowerCase(Locale.US);
        if (pattern.startsWith("*.")) {
            String suffix = pattern.substring(1);
            return host.endsWith(suffix) && host.length() > suffix.length()
                    && host.substring(0, host.length() - suffix.length()).indexOf('.') < 0;
        }
        return host.equals(pattern);
    }

    private static String mime(String s) {
        return "=?UTF-8?B?" + Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP) + "?=";
    }

    private static String wrapBase64(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i += 76) {
            b.append(s, i, Math.min(s.length(), i + 76)).append("\r\n");
        }
        return b.toString();
    }

    private static void write(OutputStream out, String s) throws Exception {
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String read(BufferedReader in) throws Exception {
        StringBuilder b = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) {
            b.append(line).append('\n');
            if (line.length() < 4 || line.charAt(3) != '-') break;
        }
        return b.toString();
    }

    private static void expect(BufferedReader in, int code) throws Exception {
        String r = read(in);
        if (!r.startsWith(String.valueOf(code))) throw new Exception("SMTP " + r.trim());
    }
}
