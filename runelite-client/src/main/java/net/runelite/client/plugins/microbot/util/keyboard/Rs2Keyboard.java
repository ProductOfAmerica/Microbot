package net.runelite.client.plugins.microbot.util.keyboard;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.input.InputArbiter;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.mouse.BotEventGuard;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.awt.event.KeyEvent.CHAR_UNDEFINED;

/**
 * Utility class for simulating keyboard input.
 */
public class Rs2Keyboard
{
	/** Keys the bot currently holds down. See {@link #releaseHeldKeys()}. */
	private static final Set<Integer> HELD_KEYS = ConcurrentHashMap.newKeySet();

	/**
	 * Gets the current game canvas.
	 *
	 * @return the game canvas
	 */
	private static Canvas getCanvas()
	{
		return Microbot.getClient().getCanvas();
	}

	/**
	 * Delivers a synthetic KeyEvent to the canvas's registered listeners directly,
	 * bypassing AWT's focus-aware dispatch pipeline. This is what eliminates the
	 * focus-steal that {@code Canvas.dispatchEvent} combined with a focusable flip
	 * used to cause.
	 *
	 * @param id       the KeyEvent type (e.g. KEY_TYPED, KEY_PRESSED, etc.)
	 * @param keyCode  the key code from {@link KeyEvent}
	 * @param keyChar  the character to type, if applicable
	 * @param delay    the delay in milliseconds before the event time is set
	 */
	private static boolean dispatchKeyEvent(int id, int keyCode, char keyChar, int delay)
	{
		// Keyboard emission never goes through InputLoop, so this is its only checkpoint. Without
		// it a takeover mid-typeString sprays the rest of the string into whatever the human just
		// took over. RELEASED is exempt: releaseHeldKeys runs while the human owns input, and
		// suppressing it would strand a key down.
		if (id != KeyEvent.KEY_RELEASED && InputArbiter.isHuman())
		{
			return false;
		}
		Canvas canvas = getCanvas();
		KeyEvent event = new KeyEvent(canvas, id, System.currentTimeMillis() + delay, 0, keyCode, keyChar);
		KeyListener[] listeners = canvas.getKeyListeners();
		// The arbiter's observe-only KeyListener is one of these, so without the guard the bot
		// reads its own keystrokes as a takeover.
		BotEventGuard.begin();
		try
		{
			for (KeyListener l : listeners)
			{
				switch (id)
				{
					case KeyEvent.KEY_TYPED:
						l.keyTyped(event);
						break;
					case KeyEvent.KEY_PRESSED:
						l.keyPressed(event);
						break;
					case KeyEvent.KEY_RELEASED:
						l.keyReleased(event);
						break;
				}
			}
		}
		finally
		{
			BotEventGuard.end();
		}
		return true;
	}

	/**
	 * Types out a string character-by-character using KEY_TYPED events.
	 * Each character is sent with a short randomized delay and sleep between characters.
	 *
	 * @param word the string to type into the game
	 */
	public static void typeString(final String word)
	{
		for (char c : word.toCharArray())
		{
			int delay = Rs2Random.logNormalBounded(20, 200);
			// Stop at the first suppressed character rather than spinning through the rest.
			if (!dispatchKeyEvent(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, c, delay))
			{
				return;
			}
			Global.sleep(Rs2Random.logNormalBounded(100, 200));
		}
	}

	/**
	 * Simulates pressing a single character using a KEY_TYPED event.
	 *
	 * @param key the character to press
	 */
	public static void keyPress(final char key)
	{
		int delay = Rs2Random.logNormalBounded(20, 200);
		dispatchKeyEvent(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, key, delay);
	}

	/**
	 * Simulates holding the Shift key using a KEY_PRESSED event.
	 */
	public static void holdShift()
	{
		int delay = Rs2Random.logNormalBounded(20, 200);
		// Only if the press went out, or releaseHeldKeys would release a key never held.
		if (dispatchKeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.VK_SHIFT, CHAR_UNDEFINED, delay))
		{
			HELD_KEYS.add(KeyEvent.VK_SHIFT);
		}
	}

	/**
	 * Simulates releasing the Shift key using a KEY_RELEASED event.
	 */
	public static void releaseShift()
	{
		int delay = Rs2Random.logNormalBounded(20, 200);
		dispatchKeyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_SHIFT, CHAR_UNDEFINED, delay);
		HELD_KEYS.remove(KeyEvent.VK_SHIFT);
	}

	/**
	 * Simulates holding down a key using a KEY_PRESSED event.
	 *
	 * @param key the key code from {@link KeyEvent}
	 */
	public static void keyHold(int key)
	{
		if (dispatchKeyEvent(KeyEvent.KEY_PRESSED, key, CHAR_UNDEFINED, 0))
		{
			HELD_KEYS.add(key);
		}
	}

	/**
	 * Simulates releasing a key using a KEY_RELEASED event.
	 *
	 * @param key the key code from {@link KeyEvent}
	 */
	public static void keyRelease(int key)
	{
		int delay = Rs2Random.logNormalBounded(20, 200);
		dispatchKeyEvent(KeyEvent.KEY_RELEASED, key, CHAR_UNDEFINED, delay);
		HELD_KEYS.remove(key);
	}

	/**
	 * Releases every key the bot still holds. A hold spans arbitrary script code rather than one
	 * gesture, so InputLoop cannot unwind it; {@code Script.run} calls this on a takeover instead.
	 *
	 * <p>Idempotent and safe from several script threads at once.
	 */
	public static void releaseHeldKeys()
	{
		for (Integer key : new ArrayList<>(HELD_KEYS))
		{
			if (HELD_KEYS.remove(key))
			{
				dispatchKeyEvent(KeyEvent.KEY_RELEASED, key, CHAR_UNDEFINED, 0);
			}
		}
	}

	static boolean isKeyHeld(int key)
	{
		return HELD_KEYS.contains(key);
	}

	/**
	 * Simulates pressing and releasing a key in quick succession.
	 *
	 * @param key the key code from {@link KeyEvent}
	 */
	public static void keyPress(int key)
	{
		char typed = toTypedChar(key);
		if (typed == CHAR_UNDEFINED)
		{
			keyHold(key);
			keyRelease(key);
			return;
		}

		dispatchKeyEvent(KeyEvent.KEY_PRESSED, key, typed, 0);
		int delay = Rs2Random.logNormalBounded(20, 200);
		dispatchKeyEvent(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, typed, delay);
		int releaseDelay = Rs2Random.between(20, 200);
		dispatchKeyEvent(KeyEvent.KEY_RELEASED, key, CHAR_UNDEFINED, releaseDelay);
	}

	/**
	 * Maps a Java {@link KeyEvent} virtual-key code to the printable character it produces
	 * when typed without modifiers. Returns {@link KeyEvent#CHAR_UNDEFINED} for non-printable keys.
	 *
	 * Needed because OSRS dialog option widgets react to {@code KEY_TYPED} char events,
	 * not the raw {@code KEY_PRESSED}/{@code KEY_RELEASED} pair that {@code keyHold}/{@code keyRelease} emit.
	 */
	static char toTypedChar(int vk)
	{
		if (vk >= KeyEvent.VK_0 && vk <= KeyEvent.VK_9) return (char) ('0' + (vk - KeyEvent.VK_0));
		if (vk >= KeyEvent.VK_A && vk <= KeyEvent.VK_Z) return (char) ('a' + (vk - KeyEvent.VK_A));
		if (vk == KeyEvent.VK_SPACE) return ' ';
		if (vk == KeyEvent.VK_ENTER) return '\n';
		if (vk == KeyEvent.VK_TAB) return '\t';
		if (vk == KeyEvent.VK_BACK_SPACE) return '\b';
		if (vk == KeyEvent.VK_ESCAPE) return (char) 27;
		return CHAR_UNDEFINED;
	}

	/**
	 * Simulates pressing the Enter key.
	 * If the player is not logged in, this uses KEY_TYPED to avoid auto-login triggers.
	 */
	public static void enter()
	{
		keyPress(KeyEvent.VK_ENTER);

		// This is to make sure the enter event gets released, because for some reason it
		// stays pressed and auto logs for jagex accounts
		resetEnter();

	}

	/**
	 * Sends a KEY_TYPED event for the Enter key to ensure it is released.
	 */
	public static void resetEnter() {
		dispatchKeyEvent(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, '\n', 10);
	}
}