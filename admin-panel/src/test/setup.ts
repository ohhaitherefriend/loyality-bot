import '@testing-library/jest-dom/vitest'

// jsdom doesn't implement these, but Radix UI's Select relies on them (pointer capture during
// open/close, scrollIntoView when navigating options with the keyboard).
if (!Element.prototype.hasPointerCapture) {
  Element.prototype.hasPointerCapture = () => false
}
if (!Element.prototype.setPointerCapture) {
  Element.prototype.setPointerCapture = () => {}
}
if (!Element.prototype.releasePointerCapture) {
  Element.prototype.releasePointerCapture = () => {}
}
if (!Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = () => {}
}
