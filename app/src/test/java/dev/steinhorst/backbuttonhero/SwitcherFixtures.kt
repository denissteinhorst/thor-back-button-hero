package dev.steinhorst.backbuttonhero

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import dev.steinhorst.backbuttonhero.core.RunningApp
import dev.steinhorst.backbuttonhero.recents.ShownCard

/** Pairs hand-built test cards with their apps as SwitcherOverlay keeps them; child 1 is the thumbnail, as in production. */
internal fun shownCards(apps: List<RunningApp>, cards: List<View>): List<ShownCard> =
    apps.zip(cards) { app, card -> ShownCard(app, card, (card as? ViewGroup)?.getChildAt(1) as? ImageView) }
