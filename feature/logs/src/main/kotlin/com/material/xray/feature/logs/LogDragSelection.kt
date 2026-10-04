package com.material.xray.feature.logs

internal class LogDragSelection(
    private val anchorId: Long,
    private val initialSelection: Set<Long>,
) {
    private val selecting = anchorId !in initialSelection

    fun selectionAt(ids: List<Long>, endId: Long): Set<Long> {
        val start = ids.indexOf(anchorId)
        val end = ids.indexOf(endId)
        if (start < 0 || end < 0) return initialSelection.intersect(ids.toSet())
        val range = ids.subList(minOf(start, end), maxOf(start, end) + 1).toSet()
        // Always apply the current range to the original selection, so reversing the gesture
        // restores rows outside the shortened range instead of leaving a trail of selected rows.
        return if (selecting) initialSelection + range else initialSelection - range
    }
}
