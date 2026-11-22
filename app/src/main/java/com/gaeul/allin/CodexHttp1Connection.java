package com.gaeul.allin;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.*;

/**
 * One HTTP/1.1 exchange over the captured network policy. Mirrors Magpie
 * codex_http1.go: ChatGPT backend traffic never negotiates h2. No retries,
 * cookies, redirects or global TLS changes; default chain and HTTPS hostname
 * verification remain enabled. OAuth/OpenAI API and other vendors are untouched.
 */
final class CodexHttp1Connection extends HttpURLConnection {
    private final NetworkPolicy.Snapshot policy;
    private final Map<String,String> requestHeaders=new LinkedHashMap<>();
    private final Map<String,List<String>> responseHeaders=new LinkedHashMap<>();
    private volatile Socket socket;
    private volatile boolean closed;
    private IOException failure;
    private final Object requestBodyLock = new Object();
    private ByteArrayOutputStream requestBody;
    private InputStream responseBody;
    private boolean responseRead;
    private String statusLine;
    private long declaredLength=-1;

    static boolean handles(String value) {
        try {
            URL u=new URL(value);
            return "https".equalsIgnoreCase(u.getProtocol())&&"chatgpt.com".equalsIgnoreCase(u.getHost())
                    &&u.getUserInfo()==null&&u.getRef()==null&&(u.getPort()==-1||u.getPort()==443);
        }catch(Exception bad){return false;}
    }
    static HttpURLConnection open(String value)throws IOException {
        if(!handles(value))return NetworkPolicy.openHttp(value);
        return new CodexHttp1Connection(new URL(value),NetworkPolicy.capture());
    }
    CodexHttp1Connection(URL u,NetworkPolicy.Snapshot policy)throws IOException {
        super(u);this.policy=policy;
        // Plain HTTP is only a local regression fixture, never selected by open().
        if(!handles(u.toString())&&!("http".equals(u.getProtocol())&&("127.0.0.1".equals(u.getHost())||"localhost".equals(u.getHost()))))
            throw new IOException("Unsupported Codex HTTP1 endpoint");
        setInstanceFollowRedirects(false);
    }
    @Override public synchronized void setRequestProperty(String key,String value) {
        if(connected)throw new IllegalStateException("Already connected");
        if(key==null||!key.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")||value==null)throw new IllegalArgumentException("Invalid HTTP header");
        for(int i=0;i<value.length();i++)if(value.charAt(i)=='\r'||value.charAt(i)=='\n'||value.charAt(i)==0)throw new IllegalArgumentException("Invalid HTTP header");
        String existing=null;for(String k:requestHeaders.keySet())if(k.equalsIgnoreCase(key)){existing=k;break;}
        if(existing!=null)requestHeaders.remove(existing);requestHeaders.put(key,value);
    }
    @Override public void addRequestProperty(String key,String value){setRequestProperty(key,value);}
    @Override public synchronized String getRequestProperty(String key){for(Map.Entry<String,String> e:requestHeaders.entrySet())if(e.getKey().equalsIgnoreCase(key))return e.getValue();return null;}
    @Override public synchronized Map<String,List<String>> getRequestProperties(){Map<String,List<String>> out=new LinkedHashMap<>();for(Map.Entry<String,String> e:requestHeaders.entrySet())out.put(e.getKey(),Collections.singletonList(e.getValue()));return Collections.unmodifiableMap(out);}
    @Override public void setFixedLengthStreamingMode(int n){super.setFixedLengthStreamingMode(n);declaredLength=n;}
    @Override public void setFixedLengthStreamingMode(long n){super.setFixedLengthStreamingMode(n);declaredLength=n;}
    @Override public boolean usingProxy(){return "http_proxy".equals(policy.mode);}
    private void checkOpen()throws IOException{if(closed||Thread.currentThread().isInterrupted())throw new IOException("Codex HTTP1 connection closed");if(failure!=null)throw failure;}
    private int connectMillis(){return getConnectTimeout()>0?getConnectTimeout():30000;}
    private int readMillis(){return getReadTimeout()>0?getReadTimeout():180000;}
    @Override public synchronized void connect()throws IOException {
        checkOpen();if(connected)return;
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(connectMillis());
        Socket opened=null;
        try {
            int port=url.getPort()>0?url.getPort():("https".equals(url.getProtocol())?443:80);
            opened=policy.connectTunnel(url.getHost(),port,connectMillis(),readMillis());socket=opened;checkOpen();
            if("https".equals(url.getProtocol())){
                // Go's default client leaves ClientSessionCache unset. A fresh context
                // avoids Android's shared default TLS session/ticket cache, without
                // changing trust managers, hostname checks or allowed TLS versions.
                SSLContext context=SSLContext.getInstance("TLS");context.init(null,null,null);
                SSLSocket tls=(SSLSocket)context.getSocketFactory().createSocket(opened,url.getHost(),port,true);
                socket=tls;opened=tls;checkOpen();
                SSLParameters parameters=tls.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");
                try{SSLParameters.class.getMethod("setApplicationProtocols",String[].class).invoke(parameters,(Object)new String[0]);}
                catch(NoSuchMethodException olderPlatform){/* Fresh raw SSLSockets offer no ALPN on these platforms. */}
                catch(ReflectiveOperationException unavailable){throw new SSLException("HTTP1 ALPN policy unavailable");}
                tls.setSSLParameters(parameters);
                if(!"HTTPS".equals(tls.getSSLParameters().getEndpointIdentificationAlgorithm()))throw new SSLException("HTTPS hostname verification unavailable");
                long left=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
                if(left<=0)throw new SocketTimeoutException("Codex TLS connect deadline");
                tls.setSoTimeout((int)Math.max(1,Math.min(left,readMillis())));tls.startHandshake();
                try{Object selected=SSLSocket.class.getMethod("getApplicationProtocol").invoke(tls);if("h2".equals(selected))throw new SSLException("Unexpected HTTP2 negotiation");}
                catch(NoSuchMethodException olderPlatform){}
                catch(ReflectiveOperationException unavailable){throw new SSLException("HTTP1 negotiation could not be checked");}
            }
            opened.setSoTimeout(readMillis());checkOpen();connected=true;
        }catch(IOException e){failure=e;if(opened!=null)try{opened.close();}catch(IOException ignored){}throw e;}
        catch(Exception e){failure=new IOException("Codex HTTP1 connection failed",e);if(opened!=null)try{opened.close();}catch(IOException ignored){}throw failure;}
    }
    @Override public synchronized OutputStream getOutputStream() throws IOException {
        if (!getDoOutput()) throw new ProtocolException("Output not enabled");
        if (responseRead) throw new ProtocolException("Codex request already sent");
        connect();
        synchronized (requestBodyLock) {
            checkOpen();
            if (requestBody == null) requestBody = new ByteArrayOutputStream();
            return requestBody;
        }
    }

    private void sendRequest() throws IOException {
        ByteArrayOutputStream body;
        synchronized (requestBodyLock) {
            checkOpen();
            body = requestBody;
            requestBody = null;
        }
        if (body == null) {
            writeRequest(null, 0);
        } else {
            synchronized (body) {
                writeRequest(body, body.size());
            }
        }
    }

    private void writeRequest(ByteArrayOutputStream body, int length) throws IOException {
        if (declaredLength >= 0 && declaredLength != length)
            throw new ProtocolException("Declared Codex body length mismatch");
        String path = url.getPath();
        if (path.isEmpty()) path = "/";
        if (url.getQuery() != null) path += "?" + url.getQuery();
        if(path.indexOf('\r')>=0||path.indexOf('\n')>=0)throw new ProtocolException("Invalid HTTP path");
        int port=url.getPort();String authority=url.getHost()+((port>0&&port!=443&&port!=80)?":"+port:"");
        StringBuilder header=new StringBuilder(method+" "+path+" HTTP/1.1\r\nHost: "+authority+"\r\nConnection: close\r\n");
        for(Map.Entry<String,String> e:requestHeaders.entrySet())if(!Arrays.asList("host","connection","content-length","transfer-encoding").contains(e.getKey().toLowerCase(Locale.ROOT)))header.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        if(getRequestProperty("Accept-Encoding")==null)header.append("Accept-Encoding: identity\r\n");
        if(getDoOutput())header.append("Content-Length: ").append(length).append("\r\n");
        header.append("\r\n");OutputStream out=socket.getOutputStream();out.write(header.toString().getBytes(StandardCharsets.UTF_8));if(length>0)body.writeTo(out);out.flush();
    }

    private synchronized void execute() throws IOException {
        if (responseRead) return;
        try {
            checkOpen();
            connect();
            sendRequest();
            BufferedInputStream input=new BufferedInputStream(socket.getInputStream(),16384);
            for(int interim=0;;interim++){
                statusLine=line(input,1024);if(!statusLine.matches("HTTP/1\\.[01] [1-5][0-9]{2}(?: [^\\r\\n]*)?"))throw new ProtocolException("Invalid Codex HTTP status");
                responseCode=Integer.parseInt(statusLine.substring(9,12));responseHeaders.clear();int budget=statusLine.length();
                for(int count=0;;count++){
                    String h=line(input,8192);budget+=h.length()+2;if(count>256||budget>65536)throw new ProtocolException("Codex response headers too large");if(h.isEmpty())break;
                    int colon=h.indexOf(':');if(colon<1)throw new ProtocolException("Invalid Codex response header");String key=h.substring(0,colon).toLowerCase(Locale.ROOT);
                    if(!key.matches("[!#$%&'*+.^_`|~0-9a-z-]+"))throw new ProtocolException("Invalid response header name");
                    responseHeaders.computeIfAbsent(key,k->new ArrayList<>()).add(h.substring(colon+1).trim());
                }
                if(responseCode<200&&responseCode!=101){if(interim>=4)throw new ProtocolException("Too many interim responses");continue;}break;
            }
            if(responseCode==101)throw new ProtocolException("Unexpected protocol upgrade");
            String length=single("content-length"),transfer=single("transfer-encoding");
            if(length!=null&&transfer!=null)throw new ProtocolException("Ambiguous Codex response framing");
            InputStream bodyIn;
            if("HEAD".equals(method)||responseCode==204||responseCode==304)bodyIn=new ByteArrayInputStream(new byte[0]);
            else if(transfer!=null){if(!"chunked".equalsIgnoreCase(transfer))throw new ProtocolException("Unsupported transfer encoding");bodyIn=new Chunked(input);}
            else if(length!=null){if(!length.matches("0|[1-9][0-9]{0,18}"))throw new ProtocolException("Invalid Content-Length");long n;try{n=Long.parseLong(length);}catch(NumberFormatException bad){throw new ProtocolException("Invalid Content-Length");}bodyIn=new Sized(input,n);}
            else bodyIn=input;
            String encoding=single("content-encoding");if(encoding!=null&&!encoding.equalsIgnoreCase("identity")){
                if(!encoding.equalsIgnoreCase("gzip"))throw new ProtocolException("Unsupported content encoding");bodyIn=new java.util.zip.GZIPInputStream(bodyIn);
            }
            responseBody=new Closing(bodyIn);responseRead=true;
        }catch(IOException e){failure=e;disconnect();throw e;}
    }
    private String single(String name)throws IOException{List<String> list=responseHeaders.get(name);if(list==null)return null;if(list.size()!=1)throw new ProtocolException("Duplicate framing header");return list.get(0);}
    private static String line(InputStream in,int max)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();int previous=-1;
        while(true){int b=in.read();if(b<0)throw new EOFException("Truncated Codex HTTP line");if(b=='\n'){if(previous!='\r')throw new ProtocolException("Invalid HTTP line ending");byte[] bytes=out.toByteArray();return new String(bytes,0,bytes.length-1,StandardCharsets.ISO_8859_1);}
            if(previous=='\r'||(b<32&&b!='\r'&&b!='\t')||out.size()>=max)throw new ProtocolException("Invalid or oversized HTTP line");out.write(b);previous=b;}
    }
    @Override public int getResponseCode()throws IOException{execute();return responseCode;}
    @Override public InputStream getInputStream()throws IOException{execute();if(responseCode>=400)throw new IOException("Codex HTTP error "+responseCode);return responseBody;}
    @Override public InputStream getErrorStream(){try{execute();return responseCode>=400?responseBody:null;}catch(IOException failed){return null;}}
    @Override public String getHeaderField(String name){try{execute();if(name==null)return statusLine;List<String> values=responseHeaders.get(name.toLowerCase(Locale.ROOT));return values==null?null:values.get(values.size()-1);}catch(IOException failed){return null;}}
    @Override public String getResponseMessage()throws IOException{execute();return statusLine.length()>13?statusLine.substring(13):"";}
    @Override public String getHeaderField(int index){try{execute();if(index==0)return statusLine;int i=1;for(List<String> values:responseHeaders.values())for(String v:values){if(i++==index)return v;}return null;}catch(IOException failed){return null;}}
    @Override public String getHeaderFieldKey(int index){try{execute();if(index==0)return null;int i=1;for(Map.Entry<String,List<String>> e:responseHeaders.entrySet())for(String ignored:e.getValue()){if(i++==index)return e.getKey();}return null;}catch(IOException failed){return null;}}
    @Override public Map<String,List<String>> getHeaderFields(){try{execute();Map<String,List<String>> copy=new LinkedHashMap<>();copy.put(null,Collections.singletonList(statusLine));for(Map.Entry<String,List<String>> e:responseHeaders.entrySet())copy.put(e.getKey(),Collections.unmodifiableList(new ArrayList<>(e.getValue())));return Collections.unmodifiableMap(copy);}catch(IOException failed){return Collections.emptyMap();}}
    @Override public void disconnect() {
        // Cancellation must not wait for execute() to finish blocking socket I/O.
        synchronized (requestBodyLock) {
            closed = true;
            requestBody = null;
        }
        Socket current = socket;
        if (current != null) try { current.close(); } catch (IOException ignored) {}
    }
    private final class Closing extends FilterInputStream {
        private boolean eof;
        Closing(InputStream in){super(in);}
        @Override public int read()throws IOException{if(eof)return -1;checkOpen();int n=super.read();if(n<0){eof=true;disconnect();}return n;}
        @Override public int read(byte[] b,int o,int l)throws IOException{if(l==0)return 0;if(eof)return -1;checkOpen();int n=super.read(b,o,l);if(n<0){eof=true;disconnect();}return n;}
        @Override public void close()throws IOException{try{in.close();}finally{disconnect();}}
    }
    private static final class Sized extends FilterInputStream {
        long remaining;Sized(InputStream in,long n){super(in);remaining=n;}
        @Override public int read()throws IOException{if(remaining==0)return -1;int n=in.read();if(n<0)throw new EOFException("Truncated Codex response");remaining--;return n;}
        @Override public int read(byte[] b,int o,int l)throws IOException{if(l==0)return 0;if(remaining==0)return -1;int n=in.read(b,o,(int)Math.min(l,remaining));if(n<0)throw new EOFException("Truncated Codex response");remaining-=n;return n;}
    }
    private static final class Chunked extends FilterInputStream {
        long left;boolean ending,ended;Chunked(InputStream in){super(in);}
        private boolean ready()throws IOException{
            if(ended)return false;if(left>0)return true;
            if(ending){if(in.read()!='\r'||in.read()!='\n')throw new ProtocolException("Invalid chunk terminator");ending=false;}
            String chunkHeader = line(in, 1024);
            int extension = chunkHeader.indexOf(';');
            int sizeEnd = extension < 0 ? chunkHeader.length() : extension;
            if (extension >= 0) {
                while (sizeEnd > 0 && (chunkHeader.charAt(sizeEnd - 1) == ' ' || chunkHeader.charAt(sizeEnd - 1) == 9)) sizeEnd--;
            }
            String size = chunkHeader.substring(0, sizeEnd);
            if(!size.matches("[0-9A-Fa-f]{1,16}"))throw new ProtocolException("Invalid chunk size");try{left=Long.parseLong(size,16);}catch(NumberFormatException bad){throw new ProtocolException("Invalid chunk size");}
            if(left==0){int total=0;while(true){String trailer=line(in,8192);total+=trailer.length()+2;if(total>65536)throw new ProtocolException("Trailers too large");if(trailer.isEmpty())break;}ended=true;return false;}ending=true;return true;
        }
        @Override public int read()throws IOException{if(!ready())return -1;int n=in.read();if(n<0)throw new EOFException("Truncated Codex chunk");left--;return n;}
        @Override public int read(byte[] b,int o,int l)throws IOException{if(l==0)return 0;if(!ready())return -1;int n=in.read(b,o,(int)Math.min(l,left));if(n<0)throw new EOFException("Truncated Codex chunk");left-=n;return n;}
    }
}
