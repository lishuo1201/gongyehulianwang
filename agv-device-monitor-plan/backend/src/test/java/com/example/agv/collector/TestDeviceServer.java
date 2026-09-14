package com.example.agv.collector;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Independent wire fixture: JDK socket 直接拼报文，不调用生产编码器或客户端库。 */
public final class TestDeviceServer implements AutoCloseable {
    private final ServerSocket server;
    private final ExecutorService workers=Executors.newFixedThreadPool(12);
    private final Set<Socket> clients=ConcurrentHashMap.newKeySet();
    public final AtomicInteger battery=new AtomicInteger(76),fault=new AtomicInteger(),heartbeat=new AtomicInteger();
    public final AtomicInteger silent=new AtomicInteger(),reads=new AtomicInteger(),delay=new AtomicInteger();
    public final AtomicBoolean frozen=new AtomicBoolean(),invalid=new AtomicBoolean(),protocolError=new AtomicBoolean();
    public final AtomicBoolean wrongUnit=new AtomicBoolean(),wrongProtocol=new AtomicBoolean(),wrongTransaction=new AtomicBoolean();
    public final AtomicInteger payloadSize=new AtomicInteger(18);
    public final ConcurrentLinkedQueue<Throwable> failures=new ConcurrentLinkedQueue<>();
    public TestDeviceServer() throws IOException {
        server=new ServerSocket(0,16,InetAddress.getLoopbackAddress());
        workers.submit(() -> {
            try { while (!server.isClosed()) { Socket socket=server.accept(); clients.add(socket); workers.submit(() -> serve(socket)); } }
            catch (IOException error) { if (!server.isClosed()) failures.add(error); }
        });
    }
    public int port() { return server.getLocalPort(); }
    public int connections() { return clients.size(); }
    private void serve(Socket socket) {
        try (socket) {
            var input=new DataInputStream(socket.getInputStream()); var output=new DataOutputStream(socket.getOutputStream());
            while (!socket.isClosed()) {
                int transaction=input.readUnsignedShort(),protocol=input.readUnsignedShort(),length=input.readUnsignedShort(),unit=input.readUnsignedByte();
                byte[] request=input.readNBytes(length-1);
                if (request.length!=length-1 || protocol!=0) throw new IOException("Malformed test request");
                reads.incrementAndGet();
                if (silent.get()==unit) continue;
                if (delay.get()>0) Thread.sleep(delay.get());
                byte[] payload;
                if (protocolError.get()) payload=new byte[]{(byte)0x83,2};
                else {
                    int heart=frozen.get() ? heartbeat.get() : heartbeat.incrementAndGet() & 65535;
                    payload=ByteBuffer.allocate(18).put((byte)3).put((byte)16).putShort((short)1)
                            .putShort((short)(fault.get()==0 ? 1 : 3)).putShort((short)(invalid.get() ? 101 : battery.get()))
                            .putShort((short)(fault.get()==0 ? 1200 : 0)).putShort((short)3).putShort((short)7)
                            .putShort((short)fault.get()).putShort((short)heart).array();
                }
                if (!protocolError.get()) payload=java.util.Arrays.copyOf(payload,payloadSize.get());
                output.writeShort(wrongTransaction.get() ? transaction+1 : transaction);output.writeShort(wrongProtocol.get() ? 1 : 0);output.writeShort(payload.length+1);
                output.writeByte(wrongUnit.get() ? unit+1 : unit);output.write(payload);output.flush();
            }
        } catch (EOFException | SocketException closed) {
            // Expected disconnect: 客户端主动关闭或截止时间到达是测试的一部分。
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (Exception failure) { failures.add(failure); }
        finally { clients.remove(socket); }
    }
    @Override public void close() throws Exception {
        server.close(); for (Socket socket:clients) socket.close(); workers.shutdownNow();
        if (!workers.awaitTermination(3,TimeUnit.SECONDS)) throw new IllegalStateException("Test server did not stop");
        if (!failures.isEmpty()) throw new AssertionError(failures.toString());
    }
}
