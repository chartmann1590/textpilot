/* TextPilot AI Messaging – Website JS */

// ── Navbar: scroll state + mobile toggle ─────────────────────
const navbar    = document.getElementById('navbar');
const navToggle = document.getElementById('navToggle');
const navLinks  = document.getElementById('navLinks');

window.addEventListener('scroll', () => {
  navbar.classList.toggle('scrolled', window.scrollY > 40);
}, { passive: true });

if (navToggle && navLinks) {
  navToggle.addEventListener('click', () => {
    const open = navLinks.classList.toggle('open');
    navToggle.setAttribute('aria-expanded', String(open));
    navToggle.setAttribute('aria-label', open ? 'Close navigation menu' : 'Open navigation menu');
  });

  // Close menu on link click
  navLinks.querySelectorAll('a').forEach(a => {
    a.addEventListener('click', () => {
      navLinks.classList.remove('open');
      navToggle.setAttribute('aria-expanded', 'false');
    });
  });
}

// ── Smooth scroll for anchor links ──────────────────────────
document.querySelectorAll('a[href^="#"]').forEach(a => {
  a.addEventListener('click', e => {
    const target = document.querySelector(a.getAttribute('href'));
    if (!target) return;
    e.preventDefault();
    const offset = parseInt(getComputedStyle(document.documentElement).getPropertyValue('--nav-h')) || 70;
    const top = target.getBoundingClientRect().top + window.scrollY - offset;
    window.scrollTo({ top, behavior: 'smooth' });
  });
});

// ── Scroll-reveal animations ─────────────────────────────────
if ('IntersectionObserver' in window) {
  const observer = new IntersectionObserver(entries => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        entry.target.classList.add('visible');
        observer.unobserve(entry.target);
      }
    });
  }, { threshold: 0.1, rootMargin: '0px 0px -40px 0px' });

  document.querySelectorAll('[data-animate]').forEach((el, i) => {
    el.style.transitionDelay = `${(i % 3) * 80}ms`;
    observer.observe(el);
  });
}

// ── Screenshot Carousel ──────────────────────────────────────
(function initCarousel() {
  const track  = document.getElementById('carouselTrack');
  const prev   = document.getElementById('carouselPrev');
  const next   = document.getElementById('carouselNext');
  const dots   = document.getElementById('carouselDots');
  if (!track) return;

  const items = Array.from(track.children);
  const VISIBLE = computeVisible();
  let current = 0;

  function computeVisible() {
    const w = window.innerWidth;
    if (w < 540) return 1;
    if (w < 768) return 2;
    if (w < 1024) return 3;
    return 4;
  }

  const totalSlides = Math.max(0, items.length - VISIBLE);

  // Build dots
  if (dots) {
    for (let i = 0; i <= totalSlides; i++) {
      const btn = document.createElement('button');
      btn.className = 'carousel-dot' + (i === 0 ? ' active' : '');
      btn.setAttribute('role', 'tab');
      btn.setAttribute('aria-label', `Screenshot ${i + 1}`);
      btn.setAttribute('aria-selected', i === 0 ? 'true' : 'false');
      btn.addEventListener('click', () => goTo(i));
      dots.appendChild(btn);
    }
  }

  function updateDots() {
    if (!dots) return;
    dots.querySelectorAll('.carousel-dot').forEach((d, i) => {
      d.classList.toggle('active', i === current);
      d.setAttribute('aria-selected', String(i === current));
    });
  }

  function getItemWidth() {
    const item = items[0];
    if (!item) return 0;
    const style = getComputedStyle(item);
    return item.offsetWidth + parseInt(style.marginRight || '0') + parseInt(style.marginLeft || '0');
  }

  function computeGap() {
    const trackStyle = getComputedStyle(track);
    return parseInt(trackStyle.gap || trackStyle.columnGap || '24') || 24;
  }

  function goTo(index) {
    current = Math.max(0, Math.min(index, totalSlides));
    const gap = computeGap();
    const itemW = getItemWidth() || (items[0]?.offsetWidth + gap) || 0;
    track.style.transform = `translateX(-${current * (itemW || 204)}px)`;
    updateDots();
    if (prev) prev.disabled = current === 0;
    if (next) next.disabled = current >= totalSlides;
  }

  // Make track a sliding flex container
  track.style.display = 'flex';
  track.style.gap = '24px';
  track.style.transition = 'transform 0.4s cubic-bezier(0.4, 0, 0.2, 1)';
  track.style.willChange = 'transform';

  if (prev) { prev.addEventListener('click', () => goTo(current - 1)); prev.disabled = true; }
  if (next) next.addEventListener('click', () => goTo(current + 1));

  goTo(0);

  // Touch/swipe support
  let startX = 0;
  track.addEventListener('touchstart', e => { startX = e.touches[0].clientX; }, { passive: true });
  track.addEventListener('touchend', e => {
    const dx = e.changedTouches[0].clientX - startX;
    if (Math.abs(dx) > 50) goTo(current + (dx < 0 ? 1 : -1));
  }, { passive: true });
})();

// ── Copy button for code block ───────────────────────────────
const copyBtn = document.getElementById('copyBtn');
if (copyBtn) {
  const code = copyBtn.closest('.code-block')?.querySelector('code');
  copyBtn.addEventListener('click', async () => {
    const text = code?.textContent || '';
    try {
      await navigator.clipboard.writeText(text);
      copyBtn.textContent = 'Copied!';
      copyBtn.classList.add('copied');
      setTimeout(() => {
        copyBtn.textContent = 'Copy';
        copyBtn.classList.remove('copied');
      }, 2000);
    } catch {
      copyBtn.textContent = 'Failed';
    }
  });
}

// ── FAQ accordion (used on faq.html) ─────────────────────────
document.querySelectorAll('.faq-question').forEach(btn => {
  btn.addEventListener('click', () => {
    const expanded = btn.getAttribute('aria-expanded') === 'true';
    // Collapse all
    document.querySelectorAll('.faq-question').forEach(b => {
      b.setAttribute('aria-expanded', 'false');
      b.nextElementSibling?.classList.remove('open');
    });
    // Open clicked (unless already open)
    if (!expanded) {
      btn.setAttribute('aria-expanded', 'true');
      btn.nextElementSibling?.classList.add('open');
    }
  });
});

// Open first FAQ item on FAQ page
const firstQ = document.querySelector('.faq-question');
if (firstQ && document.querySelector('.faq-answer')) {
  firstQ.setAttribute('aria-expanded', 'true');
  firstQ.nextElementSibling?.classList.add('open');
}
