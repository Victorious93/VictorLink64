/*
 * VictorLink64 (fork of Mupen64PlusAE, GPL-3.0). Victorious Framework.
 *
 * This file is part of VictorLink64. It is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version. It is distributed WITHOUT ANY WARRANTY; see the GNU GPL for details.
 */
package paulscode.android.mupen64plusae.input;

import paulscode.android.mupen64plusae.jni.CoreFragment;

/**
 * Feeds the shared VictorLink64 touch overlay (org.victoriousframework.victorlink64.controls) into the Mupen64Plus
 * input plug-in. It writes into the same per-player state as {@link PeripheralController}, so a hardware pad and the
 * touch controls can be used together: each source only changes the inputs it actually touched.
 */
public class OverlayController extends AbstractController
{
    private int mLastMask = 0;
    private boolean mStickWasActive = false;

    public OverlayController( CoreFragment coreFragment )
    {
        super( coreFragment );
    }

    /**
     * Applies the overlay's state.
     *
     * @param mask     N64 button bits (bit i = button index i, see the DPD_/CPD_/BTN_ constants).
     * @param axisX    Stick x, -1 (left) to 1 (right).
     * @param axisYUp  Stick y, -1 (down) to 1 (up), the plug-in's convention.
     */
    public void setState( int mask, float axisX, float axisYUp )
    {
        final int changed = mask ^ mLastMask;
        for( int i = 0; i < NUM_N64_BUTTONS; i++ )
        {
            if( ( changed & ( 1 << i ) ) != 0 )
            {
                mState.buttons[i] = ( mask & ( 1 << i ) ) != 0;
            }
        }
        mLastMask = mask;

        final boolean stickActive = axisX != 0f || axisYUp != 0f;
        if( stickActive || mStickWasActive )
        {
            mState.axisFractionX = axisX;
            mState.axisFractionY = axisYUp;
        }
        mStickWasActive = stickActive;

        notifyChanged( false );
    }
}
