package com.gaeul.allin;

import fi.iki.elonen.AllInTrackedResponse;
import java.io.*;
import java.net.HttpURLConnection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** One native request's terminal accounting and response-body ownership; never buffers a whole stream. */
final class NativeResponseLifecycle {
    final UsageLedger.Row row;
    final long startedAt;
    final RequestContext context;
    private final AtomicBoolean recorded=new AtomicBoolean();
    volatile boolean handedOff,sent;

    NativeResponseLifecycle(UsageLedger.Row row,long startedAt,RequestContext context) {
        this.row=row;this.startedAt=startedAt;this.context=context;
        sent=row!=null && row.outboundAt>0;
    }
    static NativeResponseLifecycle begin(String provider,String requested,String model,String session,RequestContext context) {
        UsageLedger.Row row=new UsageLedger.Row();
        GatewayServer.attachLiveStatsTarget(row, context);
        row.requestId=context==null?UUID.randomUUID().toString():context.requestId;
        row.prov=provider;row.req=requested==null?"":requested;row.model=model==null?"":model;
        row.session=session==null?"":session;row.ep="native";row.attempt=0;row.tries=0;
        RequestDiagnostics.begin(row,context==null?null:context.diagnosticScope,context);
        RequestDiagnostics.coverTransport(row);
        return new NativeResponseLifecycle(row,System.currentTimeMillis(),context);
    }
    void account(String id) {
        if(row==null || id==null || id.length()>256)return;
        for(int i=0;i<id.length();i++)if(Character.isISOControl(id.charAt(i)))return;
        row.accountId=id;
    }
    void markSent() {
        sent=true;
        if(row!=null){row.attempt=1;row.tries++;row.outboundAt=System.currentTimeMillis();}
        RequestDiagnostics.handoff(row);
    }
    int cancelledStatus() {
        return context!=null && (context.remainingMillis()==0 || "deadline".equals(context.cancelReason()))?504:499;
    }
    boolean clientCancelled() {
        if(context==null)return false;
        String reason=context.cancelReason();
        return !reason.isEmpty() && !"finished".equals(reason) && !"deadline".equals(reason) && context.remainingMillis()>0;
    }
    void complete(int status,String error,Boolean delivered) {
        if(row==null || !recorded.compareAndSet(false,true))return;
        // EOF is not delivery: cancellation can arrive while the writer is still flushing.
        if(status<400 && context!=null && context.isCancelled() && !"finished".equals(context.cancelReason())) {
            status=cancelledStatus();error="Native request cancelled before final delivery";delivered=false;
            context.affinitySemantic.set(false);
        }
        row.status=status;row.err=error==null?"":error;row.ms=Math.max(0,System.currentTimeMillis()-startedAt);
        row.terminal=true;
        boolean ok=status>=200 && status<300 && row.err.isEmpty();
        String failure=status==499?"cancelled":status==504?"deadline":sent?"unknown_execution":"admission";
        if(!ok) {
            row.failureKind=failure;
            if(status==499 && (row.failureReason==null || row.failureReason.isEmpty())) row.failureReason="downstream_cancelled";
            else if(status==504 && (row.failureReason==null || row.failureReason.isEmpty())) row.failureReason="read_timeout";
            else if("Native downstream write failed or cancelled".equals(row.err) && (row.failureReason==null || row.failureReason.isEmpty())) {
                row.failurePhase="downstream_delivery";
                row.transportKind="downstream";
                row.failureReason="downstream_write_failed";
            }
        }
        if(row.bridgeStatus==null || row.bridgeStatus.isEmpty()) row.bridgeStatus=ok?"native":"upstream_aborted";
        if(row.deliveryStatus==null || row.deliveryStatus.isEmpty()) {
            row.deliveryStatus=!ok?(status==499||status==504?"cancelled":"failed")
                    :(Boolean.TRUE.equals(delivered)?"delivered":(Boolean.FALSE.equals(delivered)?"failed":"unknown"));
        }
        RequestDiagnostics.outcome(row,ok,!sent,failure,false);
        if(delivered!=null)RequestDiagnostics.nativeCompletion(row,delivered && ok);
        GatewayServer.logGatewayOutcomeSummary(row);
        RequestDiagnostics.freeze(row);UsageLedger.record(row);
    }
    InputStream wrap(InputStream source,HttpURLConnection connection,int status) {
        // ByteArrayInputStream has an exact remaining length, unlike a network InputStream.
        long length=source instanceof ByteArrayInputStream?((ByteArrayInputStream)source).available():-1;
        handedOff=true;
        return new Body(source,connection,status,length);
    }
    private final class Body extends FilterInputStream implements AllInTrackedResponse.WriteCompletion {
        final HttpURLConnection connection;final long expectedLength;
        final AtomicBoolean released=new AtomicBoolean(),closed=new AtomicBoolean();
        volatile boolean writeStarted,writeFinished,eof,failed;
        volatile int status;volatile String error="";long count;
        Body(InputStream source,HttpURLConnection connection,int status,long length){super(source);this.connection=connection;this.status=status;expectedLength=length;}
        boolean completeRead(){return eof || (expectedLength>=0 && count>=expectedLength);}
        void release(){
            if(!released.compareAndSet(false,true))return;
            // Disconnect first: close on a buffered reader alone can wait behind a blocked read.
            try{if(connection!=null)connection.disconnect();}catch(Throwable ignored){}
            finally{if(context!=null && connection!=null)context.detach(connection);try{in.close();}catch(IOException ignored){}}
        }
        void fail(int code,String message){if(!failed){failed=true;status=code;error=message;}}
        void finish(Boolean delivered){complete(status,status>=400 && error.isEmpty()?"Native upstream request rejected":error,delivered);}
        void beforeRead()throws IOException{if(closed.get())throw new IOException("Native response closed");if(context!=null)context.check();}
        int observed(int n){
            if(n<0){eof=true;release();if(!writeStarted)finish(null);}
            else count+=n;
            return n;
        }
        IOException readFailed(IOException failure){
            if (row != null) GatewayServer.recordStreamThrowable(row, failure, clientCancelled());
            fail(clientCancelled()?499:context!=null && context.remainingMillis()==0?504:502,"Native response interrupted or incomplete");
            release();if(!writeStarted)finish(null);return failure;
        }
        @Override public int read()throws IOException{
            try{beforeRead();int n=in.read();observed(n<0?-1:1);return n;}catch(IOException e){throw readFailed(e);}
        }
        @Override public int read(byte[] b,int off,int len)throws IOException{
            if(b==null)throw new NullPointerException();if(off<0||len<0||off>b.length-len)throw new IndexOutOfBoundsException();if(len==0)return 0;
            try{beforeRead();return observed(in.read(b,off,len));}catch(IOException e){throw readFailed(e);}
        }
        @Override public void onWriteStart(){writeStarted=true;}
        @Override public void onWriteComplete(boolean success){
            writeFinished=true;
            if(!success)fail(cancelledStatus(),"Native downstream write failed or cancelled");
            else if(!completeRead())fail(502,"Native response incomplete");
            finish(success && completeRead() && !failed);release();
        }
        @Override public void close(){
            if(!closed.compareAndSet(false,true))return;
            if(!completeRead())fail(cancelledStatus(),"Native response cancelled before completion");
            release();if(!writeStarted || writeFinished)finish(completeRead() && !failed?null:Boolean.FALSE);
        }
    }
}
