package de.schliweb.makeacopy.bookmode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.schliweb.makeacopy.bookmode.capture.ReadyStateMachine;
import de.schliweb.makeacopy.bookmode.capture.ReadyStateMachine.State;
import org.junit.Test;

public class ReadyStateMachineTest {
  private static ReadyStateMachine sm() {
    ReadyStateMachine.Params p = new ReadyStateMachine.Params();
    p.stillThreshold = 1.0;
    p.turnThreshold = 12.0;
    p.settleMs = 300;
    p.autoHoldMs = 500;
    return new ReadyStateMachine(p);
  }

  @Test
  public void turnSettleReady() {
    ReadyStateMachine m = sm();
    assertEquals(State.TURNING, m.update(30, 1, 0.9, 0));
    assertEquals(State.SETTLING, m.update(5, 1, 0.9, 100)); // moving a little
    assertEquals(State.SETTLING, m.update(0.5, 1, 0.9, 200)); // still, but not long enough
    assertEquals(State.SETTLING, m.update(0.5, 1, 0.9, 400));
    assertEquals(State.READY, m.update(0.5, 1, 0.9, 520));
    assertFalse(m.shouldAutoCapture(900));
    assertEquals(State.READY, m.update(0.5, 1, 0.9, 1100));
    assertTrue(m.shouldAutoCapture(1100));
  }

  @Test
  public void noDoubleCapture() {
    ReadyStateMachine m = sm();
    m.update(0.1, 1, 0.9, 0);
    m.update(0.1, 1, 0.9, 400);
    assertEquals(State.READY, m.state());
    m.onCaptured();
    assertEquals(State.CAPTURED, m.state());
    assertFalse(m.isArmed());
    assertEquals(State.CAPTURED, m.update(0.1, 1, 0.9, 2000)); // stays until a page turn
    assertFalse(m.shouldAutoCapture(2000));
    assertEquals(State.TURNING, m.update(40, 0.2, 0.3, 2100));
    assertTrue(m.isArmed());
    m.update(0.1, 1, 0.9, 2200);
    assertEquals(State.READY, m.update(0.1, 1, 0.9, 2600));
  }

  @Test
  public void handOnPageOrBlurBlocksReady() {
    ReadyStateMachine m = sm();
    m.update(0.1, 1, 0.9, 0);
    assertEquals(State.SETTLING, m.update(0.1, 1, 0.3, 400)); // hand covers paper
    assertEquals(State.SETTLING, m.update(0.1, 0.5, 0.9, 500)); // soft focus
    assertEquals(State.READY, m.update(0.1, 0.9, 0.9, 600));
  }

  @Test
  public void armWithoutTurn() {
    ReadyStateMachine m = sm();
    m.onCaptured();
    m.arm();
    assertTrue(m.isArmed());
    m.update(0.1, 1, 0.9, 0);
    assertEquals(State.READY, m.update(0.1, 1, 0.9, 400));
  }
}
