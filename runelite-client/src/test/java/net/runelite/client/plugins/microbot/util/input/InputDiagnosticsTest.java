package net.runelite.client.plugins.microbot.util.input;

import net.runelite.api.Client;
import net.runelite.client.plugins.microbot.Microbot;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.awt.Canvas;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The readout has to be readable in every state, including before anything has happened. */
public class InputDiagnosticsTest
{
	private Canvas canvas;
	private Object previousClient;

	@Before
	public void before() throws Exception
	{
		canvas = new Canvas();
		Client client = mock(Client.class);
		when(client.getCanvas()).thenReturn(canvas);
		when(client.isStretchedEnabled()).thenReturn(false);
		previousClient = swapStatic("client", client);

		PointerState.reset();
		InputArbiter.resetForTest();
		CanvasInputListener.detach();
	}

	@After
	public void after() throws Exception
	{
		CanvasInputListener.detach();
		swapStatic("client", previousClient);
		PointerState.reset();
		InputArbiter.resetForTest();
	}

	@Test
	public void isOffUnlessTheSystemPropertyIsSet()
	{
		assertFalse("must stay invisible in normal use", InputDiagnostics.isEnabled());
	}

	@Test
	public void readsCleanlyBeforeAnythingHasHappened()
	{
		Map<String, String> readout = InputDiagnostics.readout();

		assertEquals("BOT", readout.get("owner"));
		assertEquals("DETACHED", readout.get("listener"));
		assertEquals("none yet", readout.get("bot point"));
		assertEquals("a distance from (-1,-1) would read as a bug", "n/a until first emit",
			readout.get("drift"));
		assertEquals("never", readout.get("last real"));
		assertEquals("none", readout.get("real held"));
	}

	@Test
	public void separatesTheThreeFaultsThatLookIdentical()
	{
		// One: the listener never attached.
		assertEquals("DETACHED", InputDiagnostics.readout().get("listener"));
		CanvasInputListener.attach();
		assertEquals("attached", InputDiagnostics.readout().get("listener"));

		// Two: motion happened but never crossed the threshold.
		PointerState.setFromBot(100, 100);
		PointerState.setFromReal(103, 104);
		InputArbiter.onRealMove(103, 104);
		Map<String, String> nearMiss = InputDiagnostics.readout();
		assertEquals("BOT", nearMiss.get("owner"));
		assertEquals("5 / 10px", nearMiss.get("drift"));

		// Three: it crossed, so anything still running is a wait ignoring the flag.
		PointerState.setFromReal(100, 120);
		InputArbiter.onRealMove(100, 120);
		Map<String, String> tripped = InputDiagnostics.readout();
		assertEquals("HUMAN", tripped.get("owner"));
		assertEquals("20 / 10px", tripped.get("drift"));
	}

	@Test
	public void namesWhatIsPhysicallyHeld()
	{
		PointerState.setFromBot(100, 100);
		InputArbiter.onRealButtonPressed(MouseEvent.BUTTON1);
		InputArbiter.onRealKeyPressed(KeyEvent.VK_SHIFT);

		assertEquals("btn1 Shift", InputDiagnostics.readout().get("real held"));

		InputArbiter.onRealButtonReleased(MouseEvent.BUTTON1);
		InputArbiter.onRealKeyReleased(KeyEvent.VK_SHIFT);
		assertEquals("none", InputDiagnostics.readout().get("real held"));
	}

	@Test
	public void distinguishesTheKillSwitchFromAnOrdinaryBotState()
	{
		PointerState.setFromBot(100, 100);
		InputArbiter.onRealButtonPressed(MouseEvent.BUTTON1);
		assertEquals("HUMAN", InputDiagnostics.readout().get("owner"));

		InputArbiter.setDisabled(true);

		assertEquals("otherwise a disabled arbiter is indistinguishable from one that never fired",
			"BOT (yielding off)", InputDiagnostics.readout().get("owner"));
	}

	@Test
	public void reportsAStaleRegistrationAfterACanvasSwap() throws Exception
	{
		CanvasInputListener.attach();
		assertEquals("attached", InputDiagnostics.readout().get("listener"));

		Client replacement = mock(Client.class);
		when(replacement.getCanvas()).thenReturn(new Canvas());
		swapStatic("client", replacement);

		assertEquals("DETACHED", InputDiagnostics.readout().get("listener"));

		CanvasInputListener.attach();
		assertEquals("attached", InputDiagnostics.readout().get("listener"));
	}

	@Test
	public void reportsIdleProgressTowardResume()
	{
		PointerState.setFromBot(100, 100);
		InputArbiter.onRealButtonPressed(MouseEvent.BUTTON1);
		InputArbiter.onRealButtonReleased(MouseEvent.BUTTON1);

		String lastReal = InputDiagnostics.readout().get("last real");
		assertTrue("expected '<elapsed> / 1800ms', got " + lastReal, lastReal.endsWith(" / 1800ms"));
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
