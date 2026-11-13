// Syntax newer than upstream intellij-rust (not part of its fixtures).
pub const trait Deref {
    type Target: ?Sized;
    fn deref(&self) -> &Self::Target;
}

const impl<T, A: [const] Allocator + [const] Destruct> Vec<T, A> {
    pub fn push(&mut self, value: T) {}
}

impl<T: ~const Clone> const Clone for Wrapper<T> {}

unsafe extern "C" {
    pub safe fn sqrt(x: f64) -> f64;
    pub unsafe fn free(p: *mut u8);
}

pub impl(crate) trait IsTerminal { fn is_terminal(&self) -> bool; }
pub impl(in crate::os) unsafe trait SocketAddrExt {}
pub impl(self) const trait TruncateTarget<Target> {}

pub trait SizeHint { final fn size_hint(&self) -> (usize, Option<usize>) { (0, None) } }

fn extender<E, T>(c: &mut E) -> impl FnMut(T) + use<'_, E, T> { |_| {} }

fn chain<F>(f: impl for<'a> [const] FnOnce(&'a usize) -> bool + [const] Destruct) where F: const FnOnce() {}

impl (i32, i32) {}

const unsafe impl<T: [const] Destruct> Drop for Vec<T> {}
pub(crate) const unsafe trait BytewiseEq<Rhs = Self>: [const] PartialEq<Rhs> + Sized {}
