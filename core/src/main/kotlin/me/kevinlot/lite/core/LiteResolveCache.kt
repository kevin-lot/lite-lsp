package me.kevinlot.lite.core

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveResult
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.containers.ContainerUtil
import java.util.concurrent.ConcurrentMap

/**
 * Resolve results per reference, dropped on any PSI change, for languages where resolving a name may need the name
 * itself (parents found through imports found through parents). A reference met again while it is being resolved
 * gets no result there. What was computed on top of that cut is kept for the running resolution only, so that it
 * stays linear; the platform cache asserts on such cycles instead.
 */
object LiteResolveCache {
    private val CACHE = Key.create<CachedValue<ConcurrentMap<PsiReference, Array<ResolveResult>>>>("lite.resolve.cache")
    private val RUNNING = ThreadLocal.withInitial(::Resolution)

    private class Frame(val reference: PsiReference, var cut: Boolean = false)

    /** The references being resolved on this thread, the outermost first, and the results that depend on a cut. */
    private class Resolution(val frames: ArrayList<Frame> = ArrayList(), val partial: HashMap<PsiReference, Array<ResolveResult>> = HashMap())

    fun resolve(project: Project, reference: PsiReference, compute: () -> Array<ResolveResult>): Array<ResolveResult> {
        val cache = cache(project)
        val running = RUNNING.get()
        (cache[reference] ?: running.partial[reference])?.let { return it }
        val first = running.frames.indexOfFirst { it.reference === reference }
        if (first >= 0) {
            running.frames.drop(first + 1).forEach { it.cut = true }
            return ResolveResult.EMPTY_ARRAY
        }
        val frame = Frame(reference)
        running.frames += frame
        try {
            val result = compute()
            when {
                frame.cut -> running.partial[reference] = result
                else -> cache[reference] = result
            }
            return result
        } finally {
            running.frames.removeAt(running.frames.size - 1)
            if (running.frames.isEmpty()) running.partial.clear()
        }
    }

    private fun cache(project: Project): ConcurrentMap<PsiReference, Array<ResolveResult>> =
        CachedValuesManager.getManager(project).getCachedValue(project, CACHE, {
            CachedValueProvider.Result.create(ContainerUtil.createConcurrentWeakKeySoftValueMap(), PsiModificationTracker.MODIFICATION_COUNT)
        }, false)
}
