package io.netty.example.gxq.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * @author xianqiu.geng
 * @Date 2021/2/24 上午10:18
 * @Copyright zhangmen
 */
public class NioServer {

    private ServerSocketChannel serverSocketChannel;
    private Selector selector;

    public NioServer() throws IOException {
        // 打开 Server Socket Channel
        serverSocketChannel = ServerSocketChannel.open();
        // 配置为非阻塞
        serverSocketChannel.configureBlocking(false);
        // 绑定 Server port
        serverSocketChannel.socket().bind(new InetSocketAddress(8080));
        // 创建 Selector
        selector = Selector.open();
        // 注册 Server Socket Channel 到 Selector
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
        System.out.println("Server 启动完成");

        handleKeys();
    }

    @SuppressWarnings("Duplicates")
    private void handleKeys() throws IOException {
        while (true) {
            // 通过 Selector 选择 Channel
            int selectNums = selector.select(30 * 1000L);
            if (selectNums == 0) {
                continue;
            }
            System.out.println("选择 Channel 数量：" + selectNums);

            // 遍历可选择的 Channel 的 SelectionKey 集合
            Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
            while (iterator.hasNext()) {
                SelectionKey key = iterator.next();
                iterator.remove(); // 移除下面要处理的 SelectionKey
                if (!key.isValid()) { // 忽略无效的 SelectionKey
                    continue;
                }

                handleKey(key);
            }
        }
    }

    private void handleKey(SelectionKey key) throws IOException {
        // 接受连接就绪
        if (key.isAcceptable()) {
            handleAcceptableKey(key);
        }
        // 读就绪
        if (key.isReadable()) {
            handleReadableKey(key);
        }
        // 写就绪
        if (key.isWritable()) {
            handleWritableKey(key);
        }
    }

    private void handleAcceptableKey(SelectionKey key) throws IOException {
        // 调用ServerSocketChannel#accept()--> 接受 Client Socket Channel
        // 获得连接的客户端的 SocketChannel
        SocketChannel clientSocketChannel = ((ServerSocketChannel) key.channel()).accept();
        // 配置为非阻塞
        clientSocketChannel.configureBlocking(false);
        // log
        System.out.println("接受新的 Channel");
        // 注册 Client Socket Channel 到 Selector
        // 为什么不对 SelectionKey.OP_WRITE 事件感兴趣呢？因为这个时候，服务端一般不会主动向客户端发送消息，所以不需要对 SelectionKey.OP_WRITE 事件感兴趣
        // 方法的第 3 个参数，我们注册了 SelectionKey 的 attachment 属性为 new ArrayList<String>()
        clientSocketChannel.register(selector, SelectionKey.OP_READ, new ArrayList<String>());
    }

    private void handleReadableKey(SelectionKey key) throws IOException {
        // Client Socket Channel
        SocketChannel clientSocketChannel = (SocketChannel) key.channel();
        // 读取数据
        ByteBuffer readBuffer = CodecUtil.read(clientSocketChannel);
        // 处理连接已经断开的情况
        if (readBuffer == null) {
            System.out.println("断开 Channel");
            // 取消注册 selector 对该客户端的 SocketChannel 的感兴趣的 IO 事件。通过调用注册方法，并且第 2 个参数 ops 为 0 ，可以达到取消注册的效果
            clientSocketChannel.register(selector, 0);
            return;
        }
        // 打印数据
        if (readBuffer.position() > 0) { // 写入模式下，
            // 格式化为字符串，并进行打印
            String content = CodecUtil.newString(readBuffer);
            System.out.println("读取数据：" + content);

            // 添加到响应队列
            List<String> responseQueue = (ArrayList<String>) key.attachment();
            responseQueue.add("响应：" + content);
            // 注册 Client Socket Channel 到 Selector
            clientSocketChannel.register(selector, SelectionKey.OP_WRITE, key.attachment());
        }
    }

    @SuppressWarnings("Duplicates")
    private void handleWritableKey(SelectionKey key) throws ClosedChannelException {
        // Client Socket Channel
        SocketChannel clientSocketChannel = (SocketChannel) key.channel();

        // 遍历响应队列
        List<String> responseQueue = (ArrayList<String>) key.attachment();
        for (String content : responseQueue) {
            // 打印数据
            System.out.println("写入数据：" + content);
            // 返回
            CodecUtil.write(clientSocketChannel, content);
        }
        responseQueue.clear();

        // 注册 Client Socket Channel 到 Selector
        clientSocketChannel.register(selector, SelectionKey.OP_READ, responseQueue);
    }

    public static void main(String[] args) throws IOException {
        NioServer server = new NioServer();
    }

}
