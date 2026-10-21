package com.fongmi.android.tv.maintenance;

import com.fongmi.android.tv.diagnostics.RingLog;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class LogUploadTest {
    @Test public void realReceiptClearsOnlyUploadedSnapshot() throws Exception { check(true, "", ""); }
    @Test public void falseReceiptDoesNotClearAnyLogs() throws Exception { check(false, "", ""); }
    @Test public void deviceInfoIsSentAsHeader() throws Exception { assertEquals("eyJtb2RlbCI6IlRWIn0", check(true, "eyJtb2RlbCI6IlRWIn0", "").get("X-Device-Info")); }
    @Test public void malformedDeviceInfoIsOmitted() throws Exception { assertNull(check(true, "bad value\r\nX-Evil: 1", "").get("X-Device-Info")); }
    @Test public void deviceIdIsSentAsHeader() throws Exception { assertEquals("K7Q2-9MZD", check(true, "", "K7Q2-9MZD").get("X-Device-Id")); }
    @Test public void malformedDeviceIdIsOmitted() throws Exception { assertNull(check(true, "", "k7q2/../x").get("X-Device-Id")); }
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); int b;
        while ((b=in.read())!=-1 && b!=10) { if(b!=13)out.write(b); }
        return out.toString("UTF-8");
    }
    /** @return the request headers the server received. */
    private Map<String,String> check(boolean valid, String deviceInfo, String deviceId) throws Exception {
        RingLog ring=new RingLog(Files.createTempDirectory("upload-test").toFile(),4096);ring.append("before");
        Map<String,String> headers=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try (ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<Void> served=new CompletableFuture<>();
            Thread worker=new Thread(()->{
                try(Socket socket=server.accept()){
                    socket.setSoTimeout(10000);InputStream in=new BufferedInputStream(socket.getInputStream());line(in);
                    String header;
                    while(!(header=line(in)).isEmpty()) { int colon=header.indexOf(':');headers.put(header.substring(0,colon),header.substring(colon+1).trim()); }
                    int length=Integer.parseInt(headers.get("Content-Length"));
                    for(int i=0;i<length;i++)if(in.read()<0)throw new EOFException();
                    ring.append("during");
                    String id=valid?headers.get("X-Batch-Id"):"wrong";
                    String sha=headers.get("X-Content-SHA256");
                    String json="{\"persisted\":true,\"batchId\":\""+id+"\",\"reportId\":\"test-report\",\"sha256\":\""+sha+"\",\"bytes\":"+length+"}";
                    byte[] body=json.getBytes(StandardCharsets.UTF_8);
                    OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
                    served.complete(null);
                }catch(Throwable e){served.completeExceptionally(e);}
            });worker.setDaemon(true);worker.start();
            String url="http://127.0.0.1:"+server.getLocalPort()+"/logs";
            if(valid)assertTrue(new LogUploader().upload(ring,url,deviceInfo,deviceId).contains("test-report"));
            else assertThrows(IOException.class,()->new LogUploader().upload(ring,url,deviceInfo,deviceId));
            served.get(10,TimeUnit.SECONDS);
            ByteArrayOutputStream out=new ByteArrayOutputStream();ring.stream(ring.snapshot(),out);
            assertEquals(valid?"during\n":"before\nduring\n",out.toString("UTF-8"));
        }
        return headers;
    }
}
