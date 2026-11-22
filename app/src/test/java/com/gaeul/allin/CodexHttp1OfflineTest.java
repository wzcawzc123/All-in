package com.gaeul.allin;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Raw HTTP/1 fixture. No external network, credential, generated certificate or files. */
public final class CodexHttp1OfflineTest {
    private static int checks;
    private static void check(boolean ok,String s){checks++;if(!ok)throw new AssertionError(s);}
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private static final class Fixture implements AutoCloseable {
        final ServerSocket listener;final Thread thread;final byte[] response;final AtomicInteger sends=new AtomicInteger();
        final CountDownLatch received = new CountDownLatch(1);
        final CountDownLatch replyAllowed;
        volatile String requestLine="",headers="",body="";volatile Throwable failure;
        Fixture(byte[] response)throws Exception{this(response, false);}
        Fixture(byte[] response, boolean waitBeforeResponse)throws Exception{
            this.response=response;replyAllowed=new CountDownLatch(waitBeforeResponse ? 1 : 0);listener=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"));thread=new Thread(this::serve);thread.setDaemon(true);thread.start();}
        void serve(){try(Socket s=listener.accept()){
            s.setSoTimeout(5000);InputStream in=s.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream();int b,state=0;
            while(state<4&&(b=in.read())>=0){out.write(b);state=state==0&&b==13?1:state==1&&b==10?2:state==2&&b==13?3:state==3&&b==10?4:0;if(out.size()>65536)throw new IOException();}
            headers=out.toString("UTF-8");
            if (state < 4) return;
            requestLine=headers.split("\r\n")[0];int length=0;
            for(String h:headers.split("\r\n"))if(h.toLowerCase().startsWith("content-length:"))length=Integer.parseInt(h.substring(15).trim());
            byte[] data=new byte[length];for(int n=0;n<length;){int x=in.read(data,n,length-n);if(x<0)throw new EOFException();n+=x;}body=new String(data,StandardCharsets.UTF_8);sends.incrementAndGet();
            received.countDown();
            if (!replyAllowed.await(5, TimeUnit.SECONDS)) throw new IOException("Fixture response gate timed out");
            if (response.length == 0) {
                if (in.read() != -1) throw new IOException("Unexpected request bytes after cancellation");
                return;
            }
            s.getOutputStream().write(response);s.getOutputStream().flush();
        }catch(Throwable e){if(!listener.isClosed())failure=e;}}
        public void close()throws Exception{replyAllowed.countDown();listener.close();thread.join(6000);check(!thread.isAlive(),"fixture stopped");if(failure!=null)throw new AssertionError("fixture",failure);}
        CodexHttp1Connection connection()throws Exception{return connection("/responses?x=1");}
        CodexHttp1Connection connection(String suffix)throws Exception{CodexHttp1Connection c=new CodexHttp1Connection(new URL("http://127.0.0.1:"+listener.getLocalPort()+suffix),NetworkPolicy.parse(null));c.setConnectTimeout(3000);c.setReadTimeout(3000);return c;}
    }
    private static String read(InputStream in)throws Exception{try(InputStream s=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[31];for(int n;(n=s.read(b))>=0;)out.write(b,0,n);check(s.read()==-1,"repeated EOF is stable");return out.toString("UTF-8");}}
    private static boolean bufferReleased(CodexHttp1Connection connection) throws Exception {
        java.lang.reflect.Field field = CodexHttp1Connection.class.getDeclaredField("requestBody");
        field.setAccessible(true);
        return field.get(connection) == null;
    }

    private static void noSecondConnection(Fixture fixture) throws IOException {
        fixture.listener.setSoTimeout(150);
        try (Socket unexpected = fixture.listener.accept()) {
            throw new AssertionError("Unexpected second physical connection");
        } catch (SocketTimeoutException expected) {
            check(true, "no second physical connection");
        }
    }

    private static void expectUnsent(CodexHttp1Connection connection) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            try { connection.getResponseCode(); throw new AssertionError("Unsent request should fail"); }
            catch (IOException expected) { check(bufferReleased(connection), "failed request buffer released"); }
        }
    }

    private static void requestBufferLifecycle() throws Exception {
        String crlf = "\r\n";
        String event = "event: response.completed\n\n";
        String reply = "HTTP/1.1 200 OK" + crlf + "Content-Type: text/event-stream" + crlf
                + "Content-Length: " + bytes(event).length + crlf + crlf + event;
        String text = "中文📦".repeat(131072);
        byte[] body = bytes(text);
        for (boolean cancel : new boolean[]{false, true}) {
            try (Fixture fixture = new Fixture(cancel ? new byte[0] : bytes(reply), true)) {
                CodexHttp1Connection connection = fixture.connection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(body); }
                check(!bufferReleased(connection), "body present before send");
                FutureTask<Integer> response = new FutureTask<>(connection::getResponseCode);
                Thread reader = new Thread(response);
                reader.setDaemon(true);
                reader.start();
                try {
                    check(fixture.received.await(3, TimeUnit.SECONDS), "peer received request before sending headers");
                    check(fixture.requestLine.startsWith("POST ") && fixture.body.equals(text) && fixture.sends.get() == 1,
                            "large UTF-8 body received intact on one POST");
                    check(!response.isDone() && bufferReleased(connection), "buffer detached while waiting for headers");
                    if (cancel) {
                        connection.disconnect();
                        fixture.replyAllowed.countDown();
                        try { response.get(2, TimeUnit.SECONDS); throw new AssertionError("Expected cancellation"); }
                        catch (ExecutionException e) { check(e.getCause() instanceof IOException, "cancellation stopped pending headers"); }
                        expectUnsent(connection);
                    } else {
                        fixture.replyAllowed.countDown();
                        check(response.get(2, TimeUnit.SECONDS) == 200, "read response status");
                        check(read(connection.getInputStream()).equals(event) && bufferReleased(connection),
                                "SSE terminal intact and body stays released");
                    }
                    noSecondConnection(fixture);
                } finally {
                    connection.disconnect();
                    fixture.replyAllowed.countDown();
                    reader.join(3500);
                }
            }
        }
        for (boolean cancelBeforeSend : new boolean[]{false, true}) {
            try (Fixture fixture = new Fixture(new byte[0])) {
                CodexHttp1Connection connection = fixture.connection();
                try {
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(body.length + (cancelBeforeSend ? 0 : 1));
                    try (OutputStream output = connection.getOutputStream()) { output.write(body); }
                    if (cancelBeforeSend) connection.disconnect();
                    expectUnsent(connection);
                    fixture.thread.join(2000);
                    check(!fixture.thread.isAlive(), "first physical connection closed");
                    check(fixture.headers.isEmpty() && fixture.sends.get() == 0, "rejected request sent no headers or body");
                    noSecondConnection(fixture);
                } finally { connection.disconnect(); }
            }
        }
    }

    public static void main(String[] args)throws Exception{
        check(CodexHttp1Connection.handles("https://chatgpt.com/backend-api/codex/responses"),"exact backend selected");
        for(String s:new String[]{"https://chatgpt.com.evil.invalid/","https://api.openai.com/v1/responses","https://auth.openai.com/oauth/token","https://daily-cloudcode-pa.googleapis.com/","http://chatgpt.com/","https://user@chatgpt.com/","https://chatgpt.com:444/"})check(!CodexHttp1Connection.handles(s),"other endpoint not changed");
        check(!(CodexHttp1Connection.open("https://api.openai.com/v1/responses") instanceof CodexHttp1Connection),"OpenAI API remains on normal transport");
        String reply="HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 5\r\nSet-Cookie: a=1\r\nSet-Cookie: b=2\r\n\r\nhello";
        try(Fixture f=new Fixture(bytes(reply))){CodexHttp1Connection c=f.connection();try{
            c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Authorization","Bearer fixture-only");byte[] data=bytes("{\"text\":\"中文\"}");c.setFixedLengthStreamingMode(data.length);try(OutputStream out=c.getOutputStream()){out.write(data);}
            check(c.getResponseCode()==200&&c.getResponseCode()==200,"status cached without retry");check("OK".equals(c.getResponseMessage()),"status message");
            check("text/plain".equals(c.getContentType()),"case-insensitive header access");check(c.getHeaderFields().get("set-cookie").size()==2,"ordinary duplicate headers retained");
            check("hello".equals(read(c.getInputStream())),"exact length body");
            check(f.requestLine.equals("POST /responses?x=1 HTTP/1.1"),"actual HTTP1 wire request");check(f.body.equals(new String(data,StandardCharsets.UTF_8)),"request UTF8 bytes unchanged");
            check(f.headers.contains("Connection: close")&&f.headers.contains("Accept-Encoding: identity"),"no shared HTTP2 connection");check(f.sends.get()==1,"one physical request");
        }finally{c.disconnect();}}
        try(Fixture f=new Fixture(bytes("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nhe\r\n3 \t;x=y\r\nllo\r\n0\r\nX-Finish: yes\r\n\r\n"))){CodexHttp1Connection c=f.connection();try{check("hello".equals(read(c.getInputStream())),"chunk extensions and trailers supported");}finally{c.disconnect();}}
        ByteArrayOutputStream gz=new ByteArrayOutputStream();try(java.util.zip.GZIPOutputStream out=new java.util.zip.GZIPOutputStream(gz)){out.write(bytes("gzip body"));}
        ByteArrayOutputStream response=new ByteArrayOutputStream();response.write(bytes("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: "+gz.size()+"\r\n\r\n"));response.write(gz.toByteArray());
        try(Fixture f=new Fixture(response.toByteArray())){CodexHttp1Connection c=f.connection();try{check("gzip body".equals(read(c.getInputStream())),"gzip decoded for existing JSON/SSE readers");}finally{c.disconnect();}}
        try(Fixture f=new Fixture(bytes("HTTP/1.1 429 Limited\r\nContent-Length: 5\r\nRetry-After: 30\r\n\r\nerror"))){CodexHttp1Connection c=f.connection();try{check(c.getResponseCode()==429&&"30".equals(c.getHeaderField("Retry-After")),"rejection facts retained");check("error".equals(read(c.getErrorStream())),"error body retained");check(f.sends.get()==1,"no rejection replay");}finally{c.disconnect();}}
        try(Fixture f=new Fixture(bytes("HTTP/1.1 302 Found\r\nLocation: https://elsewhere.invalid/\r\nContent-Length: 0\r\n\r\n"))){CodexHttp1Connection c=f.connection();try{check(!c.getInstanceFollowRedirects()&&c.getResponseCode()==302,"no credential redirect");check(f.sends.get()==1,"redirect never followed");}finally{c.disconnect();}}
        for(String broken:new String[]{"HTTP/1.1 200 OK\r\nContent-Length: 9\r\n\r\nabc","HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n", "HTTP/1.1 200 OK\r\nContent-Length: 3\r\nTransfer-Encoding: chunked\r\n\r\nabc", "HTTP/1.1 200 OK\r\nContent-Length: 3\r\nContent-Length: 3\r\n\r\nabc"}){
            try(Fixture f=new Fixture(bytes(broken))){CodexHttp1Connection c=f.connection();try{
                try{read(c.getInputStream());throw new AssertionError("truncation/ambiguous framing accepted");}catch(IOException expected){check(true,"invalid response remains failure");}
                check(f.sends.get()==1,"failed body never retried");
            }finally{c.disconnect();}}
        }
        try (Fixture fixture = new Fixture(bytes(reply))) {
            CodexHttp1Connection connection = fixture.connection("?x=1&value=a%2Fb");
            try {
                check("hello".equals(read(connection.getInputStream())), "query-only endpoint response");
                check(fixture.requestLine.equals("GET /?x=1&value=a%2Fb HTTP/1.1"), "query-only target uses slash and preserves query bytes");
            } finally { connection.disconnect(); }
        }
        requestBufferLifecycle();
        System.out.println("CodexHttp1OfflineTest PASS checks="+checks);
    }
}
