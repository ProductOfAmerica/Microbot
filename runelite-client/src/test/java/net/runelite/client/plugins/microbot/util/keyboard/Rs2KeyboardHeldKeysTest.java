package net.runelite.client.plugins.microbot.util.keyboard;

import net.runelite.api.Client;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.mouse.BotEventGuard;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.awt.Canvas;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A key the bot holds is not gesture-scoped, so InputLoop cannot unwind it. Without a release
 * path, a takeover mid-routine leaves shift stuck down at the client.
 */
public class Rs2KeyboardHeldKeysTest
{
	private Canvas canvas;
	private final List<KeyEvent> received = new ArrayList<>();
	private final List<Boolean> syntheticDuringDelivery = new ArrayList<>();
	private Object previousClient;

	@Before
	public void before() throws Exception
	{
		canvas = new Canvas();
		canvas.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent e)
			{
				record(e);
			}

			@Override
			public void keyReleased(KeyEvent e)
			{
				record(e);
			}
		});

		Client client = mock(Client.class);
		when(client.getCanvas()).thenReturn(canvas);
		when(client.isClientThread()).thenReturn(false);
		previousClient = swapStatic("client", client);

		net.runelite.client.plugins.microbot.util.input.InputArbiter.resetForTest();
		net.runelite.client.plugins.microbot.util.input.PointerState.reset();
		Rs2Keyboard.releaseHeldKeys();
		received.clear();
		syntheticDuringDelivery.clear();
	}

	@After
	public void after() throws Exception
	{
		net.runelite.client.plugins.microbot.util.input.InputArbiter.resetForTest();
		net.runelite.client.plugins.microbot.util.input.PointerState.reset();
		Rs2Keyboard.releaseHeldKeys();
		swapStatic("client", previousClient);
		while (BotEventGuard.isSynthetic())
		{
			BotEventGuard.end();
		}
	}

	@Test
	public void holdShiftIsTrackedAndReleasedOnDemand()
	{
		Rs2Keyboard.holdShift();
		assertTrue(Rs2Keyboard.isKeyHeld(KeyEvent.VK_SHIFT));

		received.clear();
		Rs2Keyboard.releaseHeldKeys();

		assertFalse(Rs2Keyboard.isKeyHeld(KeyEvent.VK_SHIFT));
		assertEquals(1, received.size());
		assertEquals(KeyEvent.KEY_RELEASED, received.get(0).getID());
		assertEquals(KeyEvent.VK_SHIFT, received.get(0).getKeyCode());
	}

	@Test
	public void aNormalReleaseClearsTheHold()
	{
		Rs2Keyboard.holdShift();
		Rs2Keyboard.releaseShift();
		assertFalse(Rs2Keyboard.isKeyHeld(KeyEvent.VK_SHIFT));

		received.clear();
		Rs2Keyboard.releaseHeldKeys();

		assertTrue("nothing left to release, so no second RELEASED for the same key", received.isEmpty());
	}

	@Test
	public void releaseHeldKeysIsIdempotent()
	{
		Rs2Keyboard.keyHold(KeyEvent.VK_W);
		Rs2Keyboard.releaseHeldKeys();
		received.clear();

		Rs2Keyboard.releaseHeldKeys();

		assertTrue(received.isEmpty());
	}

	@Test
	public void typeStringStopsAtTheCharacterWhereTheHumanTookOver()
	{
		net.runelite.client.plugins.microbot.util.input.PointerState.setFromBot(100, 100);
		net.runelite.client.plugins.microbot.util.input.InputArbiter.onRealButtonPressed(
			java.awt.event.MouseEvent.BUTTON1);

		Rs2Keyboard.typeString("myBankPin");

		// Global.sleep returns instantly under HUMAN, so without an emission-side check the rest of
		// the string lands in microseconds, in the widget the human just took.
		assertTrue("not one character may reach the canvas after the takeover", received.isEmpty());
	}

	@Test
	public void aReleaseStillGoesOutWhileTheHumanOwnsInput()
	{
		Rs2Keyboard.holdShift();
		assertTrue(Rs2Keyboard.isKeyHeld(KeyEvent.VK_SHIFT));
		received.clear();

		net.runelite.client.plugins.microbot.util.input.PointerState.setFromBot(100, 100);
		net.runelite.client.plugins.microbot.util.input.InputArbiter.onRealButtonPressed(
			java.awt.event.MouseEvent.BUTTON1);

		Rs2Keyboard.releaseHeldKeys();

		assertEquals("suppressing the release would strand shift down", 1, received.size());
		assertEquals(KeyEvent.KEY_RELEASED, received.get(0).getID());
		assertFalse(Rs2Keyboard.isKeyHeld(KeyEvent.VK_SHIFT));
	}

	@Test
	public void aSuppressedHoldIsNotRecordedAsHeld()
	{
		net.runelite.client.plugins.microbot.util.input.PointerState.setFromBot(100, 100);
		net.runelite.client.plugins.microbot.util.input.InputArbiter.onRealButtonPressed(
			java.awt.event.MouseEvent.BUTTON1);

		Rs2Keyboard.holdShift();

		assertFalse("releaseHeldKeys would then release a key that was never down",
			Rs2Keyboard.isKeyHeld(KeyEvent.VK_SHIFT));
	}

	@Test
	public void everyBotKeystrokeIsGuarded()
	{
		Rs2Keyboard.keyHold(KeyEvent.VK_W);
		Rs2Keyboard.releaseHeldKeys();

		assertFalse("no events were observed, so the check below would pass vacuously",
			syntheticDuringDelivery.isEmpty());
		for (Boolean synthetic : syntheticDuringDelivery)
		{
			assertTrue("the arbiter's listener is one of canvas.getKeyListeners(), so without the "
				+ "guard the bot reads its own keystrokes as a takeover", synthetic);
		}
		assertFalse("guard must not leak past delivery", BotEventGuard.isSynthetic());
	}

	private void record(KeyEvent event)
	{
		received.add(event);
		syntheticDuringDelivery.add(BotEventGuard.isSynthetic());
	}

	private static Object swapStatic(String name, Object value) throws Exception
	{
		Field field = Microbot.class.getDeclaredField(name);
		field.setAccessible(true);
		Object previous = field.get(null);
		field.set(null, value);
		return previous;
	}
}
