/**
 * The embed. One line on the workshop's own site:
 *
 *   <script src="https://…/widget.js" data-verkstad="dackcenter-hammarby"></script>
 *
 * Injects an iframe with the workshop's live times (ADR 0018). No styling
 * API and no keys on purpose: the page inside is public data, and one good
 * default beats a support surface.
 */
(function () {
  var script = document.currentScript;
  if (!script) return;
  var slug = script.getAttribute('data-verkstad');
  if (!slug) return;

  var origin = new URL(script.src).origin;
  var frame = document.createElement('iframe');
  frame.src = origin + '/widget/' + encodeURIComponent(slug);
  frame.title = 'Boka tid';
  frame.style.width = '100%';
  frame.style.maxWidth = '480px';
  frame.style.height = '640px';
  frame.style.border = '1px solid #dee2e6';
  frame.style.borderRadius = '8px';
  frame.loading = 'lazy';
  script.parentNode.insertBefore(frame, script);
})();
