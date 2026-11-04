// Progressive enhancement only: adds copy buttons to code blocks and drops the tab stop from
// blocks that have nothing to scroll. The page reads the same without it.
(function () {
  'use strict';

  // Scrollers are focusable in the HTML so that they still work without this script.
  function syncTabStop(scroller) {
    if (scroller === document.activeElement) return;
    if (scroller.scrollWidth > scroller.clientWidth) scroller.setAttribute('tabindex', '0');
    else scroller.removeAttribute('tabindex');
  }

  if ('ResizeObserver' in window) {
    var scrollObserver = new ResizeObserver(function (entries) {
      entries.forEach(function (entry) {
        syncTabStop(entry.target);
      });
    });
    document.querySelectorAll('.scroll').forEach(function (scroller) {
      scrollObserver.observe(scroller);
    });
  }

  var blocks = document.querySelectorAll('.snippet');
  if (!blocks.length) return;

  var status = document.createElement('p');
  status.className = 'visually-hidden';
  status.setAttribute('role', 'status');
  status.setAttribute('aria-live', 'polite');
  document.body.appendChild(status);

  // One timer, because every button shares this element: a button's own timer could blank the
  // message that another button has just written.
  var statusTimer;

  function announce(message, duration) {
    status.textContent = message;
    clearTimeout(statusTimer);
    statusTimer = setTimeout(function () {
      status.textContent = '';
    }, duration);
  }

  function legacyCopy(text) {
    // select() moves focus to the textarea, and removing it would drop focus to the top of the page.
    var focused = document.activeElement;
    var area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.top = '0';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    var copied = false;
    try {
      copied = document.execCommand('copy');
    } catch (error) {
      copied = false;
    }
    document.body.removeChild(area);
    if (focused) focused.focus({ preventScroll: true });
    if (!copied) throw new Error('Copy failed');
  }

  function copy(text) {
    if (navigator.clipboard && window.isSecureContext) {
      return navigator.clipboard.writeText(text).catch(function () {
        legacyCopy(text);
      });
    }
    return new Promise(function (resolve) {
      legacyCopy(text);
      resolve();
    });
  }

  blocks.forEach(function (block) {
    var code = block.querySelector('code');
    var caption = block.querySelector('figcaption');
    var scroller = block.querySelector('.scroll');
    if (!code || !caption || !scroller) return;

    // Several captions just say "terminal", so the scroller's label is what keeps names unique.
    var name = scroller.getAttribute('aria-label');
    var button = document.createElement('button');
    var timer;

    function show(label, message, duration) {
      button.textContent = label;
      announce(message, duration);
      clearTimeout(timer);
      timer = setTimeout(function () {
        button.textContent = 'Copy';
      }, duration);
    }

    button.type = 'button';
    button.className = 'copy';
    button.textContent = 'Copy';
    button.setAttribute('aria-label', 'Copy ' + name);

    button.addEventListener('click', function () {
      // textContent drops the version span, so a copied snippet is plain text.
      copy(code.textContent.replace(/\n$/, '')).then(
        function () {
          show('Copied', name + ' copied to clipboard', 2500);
        },
        function () {
          show('Failed, select text', 'Could not copy ' + name + '. Select the text instead.', 6000);
        }
      );
    });

    caption.appendChild(button);
  });
})();
