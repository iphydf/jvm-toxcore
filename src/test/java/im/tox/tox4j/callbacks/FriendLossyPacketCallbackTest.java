package im.tox.tox4j.callbacks;

import im.tox.tox4j.exceptions.ToxException;
import im.tox.tox4j.AliceBobTestBase;
import im.tox.tox4j.ToxCore;
import im.tox.tox4j.ToxCoreImpl;
import im.tox.tox4j.ToxOptions;
import im.tox.tox4j.exceptions.ToxNewException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FriendLossyPacketCallbackTest extends AliceBobTestBase {

    @Override
    protected ToxCore newTox(ToxOptions options) throws ToxNewException {
        return new ToxCoreImpl(options);
    }

    @Override
    protected ChatClient newClient() {
        return new Client();
    }


    private static class Client extends ChatClient {

        @Override
        public void friendConnected(final int friendNumber, boolean isConnected) {
            debug("is now connected to friend " + friendNumber);
            addTask(new Task() {
                @Override
                public void perform(ToxCore tox) throws ToxException {
                    byte[] packet = ("_My name is " + getName()).getBytes();
                    packet[0] = (byte) 200;
                    tox.sendLossyPacket(friendNumber, packet);
                }
            });
        }

        @Override
        public void friendLossyPacket(int friendNumber, byte[] packet) {
            String message = new String(packet, 1, packet.length - 1);
            debug("received a lossy packet[id=" + packet[0] + "]: " + message);
            assertEquals(friendNumber, 0);
            assertEquals((byte) 200, packet[0]);
            assertEquals("My name is " + getFriendName(), message);
            finish();
        }

    }

}