package net.runelite.client.plugins.microbot.util.input;

import net.runelite.api.Point;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.mouse.BotEventGuard;

import java.awt.AWTEvent;
import java.awt.Canvas;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Dispatches synthetic AWT mouse events on the game canvas, taking canvas coordinates.
 *
 * <p>Does not decide whether an emit is allowed; that is {@link InputArbiter}'s job, upstream.
 */
public final class AwtEmitter
{
	/** Exit coordinates can land a pixel outside the component. */
	private static final int EDGE_SLOP = 2;

	// Re-entry spread. Guesses, not a model of a person: what matters is only that the entry stops
	// being a deterministic function of the exit. Wider after a covered exit, where nothing
	// anchors the pointer.
	private static final int EDGE_SIGMA_PX = 35;
	private static final int COVERED_SIGMA_PX = 120;
	private static final double COVERED_EDGE_RETURN_CHANCE = 0.35;

	private AwtEmitter()
	{
	}

	public static void moved(int canvasX, int canvasY)
	{
		Canvas canvas = canvas();
		if (canvas == null)
		{
			return;
		}
		enterIfOutside(canvas, canvasX, canvasY);
		Point component = StretchMapper.toComponent(canvasX, canvasY);
		recordPosition(canvasX, canvasY);
		dispatch(canvas, new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0,
			component.getX(), component.getY(), 0, false));
	}

	public static void pressed(int canvasX, int canvasY, int button)
	{
		button(MouseEvent.MOUSE_PRESSED, canvasX, canvasY, button);
	}

	public static void released(int canvasX, int canvasY, int button)
	{
		button(MouseEvent.MOUSE_RELEASED, canvasX, canvasY, button);
	}

	public static void clicked(int canvasX, int canvasY, int button)
	{
		button(MouseEvent.MOUSE_CLICKED, canvasX, canvasY, button);
	}

	public static void wheel(int canvasX, int canvasY, int wheelRotation, int unitsToScroll)
	{
		Canvas canvas = canvas();
		if (canvas == null)
		{
			return;
		}
		enterIfOutside(canvas, canvasX, canvasY);
		Point component = StretchMapper.toComponent(canvasX, canvasY);
		recordPosition(canvasX, canvasY);
		dispatch(canvas, new MouseWheelEvent(canvas, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0,
			component.getX(), component.getY(), 0, false, 0, unitsToScroll, wheelRotation));
	}

	private static void button(int id, int canvasX, int canvasY, int button)
	{
		Canvas canvas = canvas();
		if (canvas == null)
		{
			return;
		}
		enterIfOutside(canvas, canvasX, canvasY);
		Point component = StretchMapper.toComponent(canvasX, canvasY);
		recordPosition(canvasX, canvasY);
		dispatch(canvas, new MouseEvent(canvas, id, System.currentTimeMillis(), 0,
			component.getX(), component.getY(), 1, false, button));
	}

	/**
	 * Announces the pointer's return before any other event, if the client believes none is over
	 * the canvas.
	 *
	 * <p>A real MOUSE_EXITED leaves the client tracking (-1,-1). Emitting a bare MOVED or PRESSED
	 * after that delivers motion for a pointer it does not think exists.
	 */
	private static void enterIfOutside(Canvas canvas, int fallbackX, int fallbackY)
	{
		if (!PointerState.isOutside())
		{
			return;
		}
		// Cleared first: the ENTERED below must not re-enter this method.
		PointerState.markInside();

		Point at = PointerState.get();
		int exitX = at.getX() < 0 ? fallbackX : at.getX();
		int exitY = at.getY() < 0 ? fallbackY : at.getY();

		Point entry = reentryPoint(exitX, exitY, canvasWidth(), canvasHeight(), ThreadLocalRandom.current());
		Point component = StretchMapper.toComponent(entry.getX(), entry.getY());
		// Recorded as well as sent, or the next click presses at a point the pointer never
		// travelled to, with no MOVED in between.
		recordPosition(entry.getX(), entry.getY());
		dispatch(canvas, new MouseEvent(canvas, MouseEvent.MOUSE_ENTERED, System.currentTimeMillis(), 0,
			component.getX(), component.getY(), 0, false));
	}

	/**
	 * Where the pointer comes back in, given where it went out. Two departures produce a
	 * MOUSE_EXITED and they want different answers.
	 *
	 * <p>On an edge, the pointer travelled out and has been elsewhere since, so it returns through
	 * that edge at a different point along it. Away from any edge a window covered the canvas and
	 * the position is unknown, so the return is drawn either from inside or across an edge.
	 * Returning to the exact exit point stays reachable: that is the user who never touched the
	 * mouse.
	 *
	 * <p>Randomness is a parameter so tests can exercise the distribution.
	 */
	static Point reentryPoint(int exitX, int exitY, int width, int height, Random random)
	{
		if (width <= 0 || height <= 0)
		{
			return new Point(exitX, exitY);
		}

		boolean onVerticalEdge = exitX <= EDGE_SLOP || exitX >= width - 1 - EDGE_SLOP;
		boolean onHorizontalEdge = exitY <= EDGE_SLOP || exitY >= height - 1 - EDGE_SLOP;

		if (onVerticalEdge)
		{
			return new Point(clamp(exitX, width), clamp(gaussian(random, exitY, EDGE_SIGMA_PX), height));
		}
		if (onHorizontalEdge)
		{
			return new Point(clamp(gaussian(random, exitX, EDGE_SIGMA_PX), width), clamp(exitY, height));
		}

		if (random.nextDouble() < COVERED_EDGE_RETURN_CHANCE)
		{
			return randomEdgePoint(width, height, random);
		}
		return new Point(clamp(gaussian(random, exitX, COVERED_SIGMA_PX), width),
			clamp(gaussian(random, exitY, COVERED_SIGMA_PX), height));
	}

	private static Point randomEdgePoint(int width, int height, Random random)
	{
		switch (random.nextInt(4))
		{
			case 0:
				return new Point(0, random.nextInt(height));
			case 1:
				return new Point(width - 1, random.nextInt(height));
			case 2:
				return new Point(random.nextInt(width), 0);
			default:
				return new Point(random.nextInt(width), height - 1);
		}
	}

	private static int gaussian(Random random, int mean, int sigma)
	{
		return (int) Math.round(mean + random.nextGaussian() * sigma);
	}

	private static int clamp(int value, int size)
	{
		return Math.max(0, Math.min(size - 1, value));
	}

	private static int canvasWidth()
	{
		try
		{
			return Microbot.getClient() == null ? 0 : Microbot.getClient().getCanvasWidth();
		}
		catch (Exception ex)
		{
			return 0;
		}
	}

	private static int canvasHeight()
	{
		try
		{
			return Microbot.getClient() == null ? 0 : Microbot.getClient().getCanvasHeight();
		}
		catch (Exception ex)
		{
			return 0;
		}
	}

	/**
	 * Real events win: while the human owns input a synthetic emit must not move the recorded
	 * position. The abort path still emits its RELEASED, it just does not drag the position along.
	 */
	private static void recordPosition(int canvasX, int canvasY)
	{
		if (InputArbiter.isHuman())
		{
			return;
		}
		PointerState.setFromBot(canvasX, canvasY);
	}

	private static Canvas canvas()
	{
		try
		{
			return Microbot.getClient() == null ? null : Microbot.getClient().getCanvas();
		}
		catch (Exception ex)
		{
			return null;
		}
	}

	// Jagex's MOUSE_PRESSED listener calls canvas.requestFocus(), stealing OS focus from whatever
	// the user is typing in. Non-focusable for the dispatch neuters it; mouse delivery does not
	// care about focusable state.
	//
	// Only when the canvas is not already the focus owner. If it is, meaning the user is typing in
	// game chat, setFocusable(false) hands focus to the parent, which is the very thing this
	// prevents.
	private static void dispatch(Canvas canvas, AWTEvent event)
	{
		boolean canvasIsFocused = canvas.isFocusOwner();
		boolean wasFocusable = canvas.isFocusable();
		boolean shouldGuard = wasFocusable && !canvasIsFocused;
		if (shouldGuard)
		{
			canvas.setFocusable(false);
		}
		BotEventGuard.begin();
		try
		{
			canvas.dispatchEvent(event);
		}
		finally
		{
			BotEventGuard.end();
			if (shouldGuard)
			{
				canvas.setFocusable(true);
			}
		}
	}
}
