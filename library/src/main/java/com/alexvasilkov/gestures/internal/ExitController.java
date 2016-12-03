package com.alexvasilkov.gestures.internal;

import android.graphics.Point;
import android.graphics.RectF;
import android.view.View;

import com.alexvasilkov.gestures.GestureController;
import com.alexvasilkov.gestures.GestureControllerForPager;
import com.alexvasilkov.gestures.State;
import com.alexvasilkov.gestures.animation.ViewPositionAnimator;
import com.alexvasilkov.gestures.utils.GravityUtils;
import com.alexvasilkov.gestures.utils.MathUtils;
import com.alexvasilkov.gestures.views.interfaces.AnimatorView;

public class ExitController {

    private static final float SCROLL_FACTOR = 2f;
    private static final float SCROLL_THRESHOLD = 30f;

    private static final float ZOOM_FACTOR = 0.75f;
    private static final float ZOOM_THRESHOLD = 0.75f;

    private static final float EXIT_THRESHOLD = 0.75f;

    private static final float MIN_EXIT_STATE = 0.01f; // Ensure we'll not hit 0 accidentally

    // Temporary objects
    private static final RectF tmpArea = new RectF();
    private static final Point tmpPivot = new Point();

    private final float scrollThresholdScaled;

    private final GestureController controller;
    private final AnimatorView animatorView;

    private float exitState = 1f; // 1f - fully opened, 0f - fully closed

    private boolean isZoomInAction;
    private boolean isRotationInAction;

    private boolean skipZoomDetection;

    private boolean isScrollDetected;
    private boolean isZoomDetected;

    private float scrollAccumulator;
    private float zoomAccumulator = 1f;

    private float scrollDirection;
    private float initialY;
    private float initialZoom;


    public ExitController(View view, GestureController gestureController) {
        controller = gestureController;
        animatorView = view instanceof AnimatorView ? ((AnimatorView) view) : null;

        scrollThresholdScaled = UnitsUtils.toPixels(view.getContext(), SCROLL_THRESHOLD);
    }

    public boolean isExitDetected() {
        return isScrollDetected || isZoomDetected;
    }

    public void onUpOrCancel() {
        finishDetection();
    }

    /**
     * @return true if scroll was consumed, false otherwise.
     */
    public boolean onScroll(float dy) {
        // Exit by scroll should not be detected if zoom or rotation is currently in place.
        // Also, we can detect scroll only if image is zoomed out and it reached movement bounds.

        if (!isExitDetected() && canDetectExit() && !isZoomInAction && !isRotationInAction
                && isZoomedOut() && !canScroll(dy)) {

            // Waiting until we scrolled enough to trigger exit detection
            scrollAccumulator += dy;
            if (Math.abs(scrollAccumulator) > scrollThresholdScaled) {
                isScrollDetected = true;
                initialY = controller.getState().getY();
                startDetection();
            }
        }

        if (isScrollDetected) {
            // Initializing scroll direction with current direction, if not initialized yet
            if (scrollDirection == 0f) {
                scrollDirection = Math.signum(dy);
            }

            // Updating exit state depending on the amount scrolled in relation to total height
            final float height = controller.getSettings().getMovementAreaH();
            final float total = scrollDirection * height / SCROLL_FACTOR;

            exitState = 1f - (controller.getState().getY() + dy - initialY) / total;
            exitState = MathUtils.restrict(exitState, MIN_EXIT_STATE, 1f);

            if (exitState == 1f) {
                // Scrolling to initial position
                controller.getState().translateTo(controller.getState().getX(), initialY);
            } else {
                // Applying scrolled distance
                controller.getState().translateBy(0f, dy);
            }

            updateState();

            if (exitState == 1f) {
                finishDetection();
            }
            return true;
        }

        return isExitDetected();
    }

    /**
     * @return true if fling was consumed, false otherwise.
     */
    public boolean onFling() {
        return isExitDetected();
    }

    public void onScaleBegin() {
        isZoomInAction = true;
    }

    public void onScaleEnd() {
        isZoomInAction = false;
        skipZoomDetection = false;
        if (isZoomDetected) {
            finishDetection();
        }
    }

    /**
     * @return true if scale was consumed, false otherwise.
     */
    public boolean onScale(float scaleFactor) {
        // Exit by zoom should not be detected if rotation is currently in place.
        // Also, we can detect zoom only if image is zoomed out and we are zooming out.

        if (!isZoomedOut()) {
            skipZoomDetection = true;
        }

        if (!skipZoomDetection && !isExitDetected() && canDetectExit() && !isRotationInAction
                && isZoomedOut() && scaleFactor < 1f) {

            // Waiting until we zoomed enough to trigger exit detection
            zoomAccumulator *= scaleFactor;
            if (zoomAccumulator < ZOOM_THRESHOLD) {
                isZoomDetected = true;
                initialZoom = controller.getState().getZoom();
                startDetection();
            }
        }

        if (isZoomDetected) {
            // Updating exit state by applying zoom factor
            exitState = controller.getState().getZoom() * scaleFactor / initialZoom;
            exitState = MathUtils.restrict(exitState, MIN_EXIT_STATE, 1f);

            GravityUtils.getDefaultPivot(controller.getSettings(), tmpPivot);

            if (exitState == 1f) {
                // Zooming to initial level using default pivot point
                controller.getState().zoomTo(initialZoom, tmpPivot.x, tmpPivot.y);
            } else {
                // Applying zoom factor using default pivot point
                final float scaleFactorFixed = 1f + (scaleFactor - 1f) * ZOOM_FACTOR;
                controller.getState().zoomBy(scaleFactorFixed, tmpPivot.x, tmpPivot.y);
            }

            updateState();

            if (exitState == 1f) {
                finishDetection();
                return true;
            }
        }

        return isExitDetected();
    }

    public void onRotationBegin() {
        isRotationInAction = true;
    }

    public void onRotationEnd() {
        isRotationInAction = false;
    }

    /**
     * @return true if rotation was consumed, false otherwise.
     */
    public boolean onRotate() {
        return isExitDetected();
    }


    private boolean canDetectExit() {
        return controller.getSettings().isExitEnabled() && animatorView != null
                && !animatorView.getPositionAnimator().isLeaving();
    }

    private boolean canScroll(float dy) {
        if (!controller.getSettings().isRestrictBounds()) {
            return true;
        }
        final State state = controller.getState();
        controller.getStateController().getMovementArea(state, tmpArea);
        return (dy > 0f && State.compare(state.getY(), tmpArea.bottom) < 0f)
                || (dy < 0f && State.compare(state.getY(), tmpArea.top) > 0f);
    }

    private boolean isZoomedOut() {
        final State state = controller.getState();
        final float minZoom = controller.getStateController().getMinZoom(state);
        return State.compare(state.getZoom(), minZoom) <= 0;
    }


    private void startDetection() {
        controller.getSettings().disableBounds();

        if (controller instanceof GestureControllerForPager) {
            ((GestureControllerForPager) controller).disableViewPager(true);
        }
    }

    private void finishDetection() {
        if (isExitDetected()) {
            if (controller instanceof GestureControllerForPager) {
                ((GestureControllerForPager) controller).disableViewPager(false);
            }

            controller.getSettings().enableBounds();

            final ViewPositionAnimator animator = animatorView.getPositionAnimator();

            if (!animator.isAnimating()) { // In case exit animation is already in place
                final float position = animator.getPositionState();
                final boolean isLeaving = position < EXIT_THRESHOLD;

                if (isLeaving) {
                    animator.exit(true);
                } else {
                    final float y = controller.getState().getY();
                    final float zoom = controller.getState().getZoom();
                    final boolean isScrolledBack = isScrollDetected && State.equals(y, initialY);
                    final boolean isZoomedBack = isZoomDetected && State.equals(zoom, initialZoom);

                    if (position < 1f) {
                        animator.setState(position, false, true);

                        // Animating bounds if user didn't scroll or zoom to initial position
                        // manually. We will also need to temporary re-enable bounds restrictions
                        // disabled by position animator (a bit hacky though).
                        if (!isScrolledBack && !isZoomedBack) {
                            controller.getSettings().enableBounds();
                            controller.animateKeepInBounds();
                            controller.getSettings().disableBounds();
                        }
                    }
                }
            }
        }

        isScrollDetected = false;
        isZoomDetected = false;
        exitState = 1f;
        scrollDirection = 0f;
        scrollAccumulator = 0f;
        zoomAccumulator = 1f;
    }

    private void updateState() {
        animatorView.getPositionAnimator().setToState(controller.getState(), exitState);
        animatorView.getPositionAnimator().setState(exitState, false, false);
    }

}