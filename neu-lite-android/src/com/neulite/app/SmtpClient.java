package com.neulite.app;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import android.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** 极简 SMTP 发信：只支持文本邮件，用于校园邮箱客户端专用密码。 */
final class SmtpClient {
    private SmtpClient() {}

    static void send(String from, String password, String to, String subject, String body) throws Exception {
        Exception last = null;
        String[][] hosts = {{"smtp.mails.neu.edu.cn", "465"}, {"smtp.neu.edu.cn", "465"}};
        for (String[] hp : hosts) {
            try {
                sendOne(hp[0], Integer.parseInt(hp[1]), from, password, to, subject, body);
                return;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last == null ? new Exception("SMTP 连接失败") : last;
    }

    private static void sendOne(String host, int port, String from, String password,
                                String to, String subject, String body) throws Exception {
        SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
        socket.connect(new InetSocketAddress(host, port), 12000);
        socket.setSoTimeout(15000);
        try {
            SSLParameters p = socket.getSSLParameters();
            p.setServerNames(Collections.singletonList(new SNIHostName(host)));
            socket.setSSLParameters(p);
        } catch (Exception ignored) {}
        socket.startHandshake();
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        OutputStream out = socket.getOutputStream();

        expect(in, 220);
        write(out, "EHLO neu-lite\r\n");
        String ehlo = read(in); // 250 multi-line
        if (!ehlo.startsWith("250")) throw new Exception("SMTP EHLO: " + ehlo);

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
        String msg = "From: " + mime("日在NEU") + " <" + from + ">\r\n"
                + "To: <" + to + ">\r\n"
                + "Subject: " + mime(subject) + "\r\n"
                + "Date: " + date + "\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: base64\r\n"
                + "\r\n"
                + wrapBase64(Base64.encodeToString(body.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP))
                + "\r\n.\r\n";
        write(out, msg);
        expect(in, 250);
        write(out, "QUIT\r\n");
        socket.close();
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
