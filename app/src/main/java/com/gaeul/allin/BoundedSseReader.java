package com.gaeul.allin;

import java.io.*;
import java.util.concurrent.atomic.AtomicLong;

/** Incremental, bounded SSE framing. There is deliberately no whole-stream size limit.
 * Reservations cover builders, decoded strings and the current downstream frame until the
 * caller requests its next frame/line or closes. Oversubscription fails closed, never waits.
 */
final class BoundedSseReader extends BufferedReader {
    static final int MAX_LINE_CHARS=256*1024, MAX_EVENT_CHARS=512*1024;
    static final long MAX_ACTIVE_BYTES=16L*1024*1024;
    private static final AtomicLong ACTIVE=new AtomicLong();
    private static final long RESERVATION_BYTES=4096L*8;
    private long held,used;
    private boolean skipLf,closed;
    private final int maxLine;
    BoundedSseReader(Reader input){this(input,MAX_LINE_CHARS);}
    BoundedSseReader(Reader input,int limit){super(input,4096);maxLine=limit;}
    static long activeBytes(){return ACTIVE.get();}
    private void reserve()throws IOException {
        // Conservative eight bytes per character includes builder capacity/copies and UTF-8.
        // Reserve a bounded chunk; only replenishment touches the global atomic.
        if(used==held) for(;;) {
            long current=ACTIVE.get();
            long amount=Math.min(RESERVATION_BYTES,MAX_ACTIVE_BYTES-current);
            amount-=amount%8;
            if(amount<8)throw new IOException("SSE active framing budget exceeded");
            if(ACTIVE.compareAndSet(current,current+amount)){held+=amount;break;}
        }
        used+=8;
    }
    private void release(){long n=held;held=0;used=0;if(n!=0)ACTIVE.addAndGet(-n);}
    private String line()throws IOException {
        StringBuilder b=new StringBuilder();
        for(;;){int c=super.read();if(skipLf){skipLf=false;if(c=='\n')continue;}
            if(c<0)return b.length()==0?null:b.toString();
            if(c=='\r'){skipLf=true;return b.toString();}if(c=='\n')return b.toString();
            if(b.length()>=maxLine)throw new IOException("SSE line buffer limit exceeded");
            reserve();b.append((char)c);
        }
    }
    @Override public String readLine()throws IOException {
        release();try{return line();}catch(IOException e){release();throw e;}
    }
    static final class Event {
        final String data,metadata;final boolean hasData;
        Event(String d,String m,boolean h){data=d;metadata=m;hasData=h;}
        String wire(String transformed){StringBuilder out=new StringBuilder(metadata);
            if(hasData){String[] lines=transformed.split("\n",-1);for(String line:lines)out.append("data: ").append(line).append('\n');}
            return out.append('\n').toString();
        }
    }
    Event readEvent()throws IOException {
        release();StringBuilder data=new StringBuilder(),meta=new StringBuilder();boolean has=false,any=false;int size=0;
        try {for(;;){String l=line();if (l == null) {
                if (has) throw new EOFException("Truncated SSE event before blank line");
                release();
                return null;
            }
            if(l.isEmpty()){if(any)return new Event(data.toString(),meta.toString(),has);continue;}
            any=true;size+=l.length()+1;if(size>MAX_EVENT_CHARS)throw new IOException("SSE event buffer limit exceeded");
            int colon=l.indexOf(':');String field=colon<0?l:l.substring(0,colon);String value=colon<0?"":l.substring(colon+1);if(value.startsWith(" "))value=value.substring(1);
            if(field.equals("data")){if(has)data.append('\n');data.append(value);has=true;}
            else meta.append(l).append('\n');
        }}catch(IOException e){release();throw e;}
    }
    @Override public void close()throws IOException {if(closed)return;closed=true;try{super.close();}finally{release();}}
}
