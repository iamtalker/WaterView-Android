package kr.iamtalker.waterview

/**
 * The viewer as WaterView's own screens open it: see [ViewerActivity.INTERNAL_VIEWER]. A class
 * rather than an alias, because an alias takes on the viewer's documentLaunchMode, and a file
 * opened inside WaterView belongs in WaterView's card in the recent apps screen, not in one of its own.
 */
class InternalViewer : ViewerActivity()
