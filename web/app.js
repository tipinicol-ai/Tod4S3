const modal = document.querySelector('#requestModal');
const form = document.querySelector('#requestForm');
const selectedPlan = document.querySelector('#selectedPlan');
const closeButton = document.querySelector('.modal-close');
const requestFields = document.querySelector('#requestFields');
const modalTitle = document.querySelector('#modalTitle');
const modalLede = document.querySelector('#modalLede');
const submitLabel = document.querySelector('.request-submit .request-label');
const adminRadios = [...document.querySelectorAll('input[name="admin"]')];
let genericContactMode = false;
let modalOpener = null;

function visitorId() {
  try {
    let id = localStorage.getItem('toduss3_visitor_id');
    if (!id) {
      id = (crypto.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`);
      localStorage.setItem('toduss3_visitor_id', id);
    }
    return id;
  } catch {
    return `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  }
}

function formatUptime(seconds) {
  const total = Math.max(0, Number(seconds) || 0);
  const days = Math.floor(total / 86400);
  const hours = Math.floor((total % 86400) / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  if (days) return `${days}d ${hours}h`;
  if (hours) return `${hours}h ${minutes}m`;
  return `${minutes}m`;
}

async function refreshSiteStats() {
  try {
    const response = await fetch(`/api/site-stats?visitor=${encodeURIComponent(visitorId())}`, { cache: 'no-store' });
    if (!response.ok) return;
    const stats = await response.json();
    document.querySelector('#totalVisits').textContent = Number(stats.total_visits || 0).toLocaleString('es');
    document.querySelector('#onlineNow').textContent = Number(stats.online_now || 0).toLocaleString('es');
    document.querySelector('#siteUptime').textContent = formatUptime(stats.uptime_seconds);
  } catch {
    // El contador es informativo; no debe afectar la navegación ni las compras.
  }
}

refreshSiteStats();
window.setInterval(refreshSiteStats, 10000);

function setContactMode(isGeneric) {
  genericContactMode = isGeneric;
  if (requestFields) {
    requestFields.hidden = isGeneric;
    requestFields.querySelectorAll('input, select').forEach((control) => {
      control.disabled = isGeneric;
    });
  }
  if (modalTitle) modalTitle.textContent = isGeneric ? 'Contacta con el equipo.' : 'Adquirir un plan.';
  if (modalLede) {
    modalLede.textContent = isGeneric
      ? 'Elige un administrador para abrir Telegram y consultar sobre los planes Premium.'
      : 'Completa tus datos, elige un administrador y continúa en Telegram. Puedes pagar por Transfermóvil o saldo móvil; después, envía la captura del comprobante para activar tu plan.';
  }
  if (submitLabel) submitLabel.textContent = isGeneric ? 'Abrir Telegram' : 'Abrir chat directo';
  const pickerNote = document.querySelector('.admin-picker-note');
  if (pickerNote) {
    pickerNote.textContent = isGeneric
      ? 'Abriremos Telegram con un mensaje general sobre los planes.'
      : 'Abriremos Telegram con tu solicitud preparada.';
  }
}

function openRequest(plan, isGeneric = false, opener = document.activeElement) {
  if (!modal || !form) return;
  modalOpener = opener;
  if (!isGeneric && selectedPlan) selectedPlan.value = plan || 'plan solicitado';
  setContactMode(isGeneric);
  adminRadios.forEach((radio) => { radio.checked = false; });
  modal.hidden = false;
  document.body.classList.add('modal-open');
  window.setTimeout(() => {
    const initialFocus = isGeneric ? adminRadios[0] : modal.querySelector('input[name="name"]');
    initialFocus?.focus();
  }, 80);
}

function closeRequest() {
  if (!modal) return;
  modal.hidden = true;
  document.body.classList.remove('modal-open');
  modalOpener?.focus?.();
}

document.querySelectorAll('.plan-button').forEach((button) => {
  button.addEventListener('click', (event) => {
    event.preventDefault();
    openRequest(button.dataset.plan || 'plan solicitado', false, button);
  });
});

document.querySelector('[data-open-admin-picker]')?.addEventListener('click', (event) => {
  event.preventDefault();
  openRequest('', true, event.currentTarget);
});

closeButton?.addEventListener('click', closeRequest);
modal?.addEventListener('click', (event) => {
  if (event.target === modal) closeRequest();
});
document.addEventListener('keydown', (event) => {
  if (modal?.hidden) return;
  if (event.key === 'Escape') {
    closeRequest();
    return;
  }
  if (event.key !== 'Tab') return;
  const focusable = [...modal.querySelectorAll('button:not([disabled]), input:not([disabled]), select:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])')]
    .filter((element) => !element.closest('[hidden]') && element.getClientRects().length);
  if (!focusable.length) return;
  const first = focusable[0];
  const last = focusable[focusable.length - 1];
  if (event.shiftKey && (document.activeElement === first || !modal.contains(document.activeElement))) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && (document.activeElement === last || !modal.contains(document.activeElement))) {
    event.preventDefault();
    first.focus();
  }
});

function openTelegramForHandle(handle, message) {
  window.open(`https://t.me/${encodeURIComponent(handle)}?text=${encodeURIComponent(message)}`, '_blank', 'noopener,noreferrer');
  closeRequest();
}

function requestMessage() {
  if (genericContactMode) {
    return 'Hola, quisiera recibir información sobre los planes Premium de ToDus S3.';
  }
  const data = new FormData(form);
  const plan = String(data.get('plan') || 'plan solicitado').trim();
  const name = String(data.get('name') || '').trim();
  const username = String(data.get('username') || '').trim() || 'No indicado';
  const payment = String(data.get('payment') || '').trim();
  if (!payment) {
    form.querySelector('select[name="payment"]')?.focus();
    return null;
  }
  return [
    'Hola, deseo solicitar este plan Premium:',
    '',
    'Plan: ' + plan,
    'Nombre: ' + name,
    'Usuario de Telegram: ' + username,
    'Método de pago: ' + payment,
    '',
    'Realizaré el pago por el método indicado y enviaré la captura del comprobante por este chat para revisión y activación manual.',
  ].join('\n');
}

form?.addEventListener('submit', (event) => {
  event.preventDefault();
  const message = requestMessage();
  const handle = new FormData(form).get('admin');
  if (message && handle) openTelegramForHandle(String(handle), message);
});

const revealItems = document.querySelectorAll('.plan-card, .visual-card, .step, .limits-panel, .final-cta');
if ('IntersectionObserver' in window) {
  revealItems.forEach((item) => item.classList.add('reveal-ready'));
  const observer = new IntersectionObserver((entries, currentObserver) => {
    entries.forEach((entry) => {
      if (!entry.isIntersecting) return;
      entry.target.classList.add('is-visible');
      currentObserver.unobserve(entry.target);
    });
  }, { threshold: 0.12, rootMargin: '0px 0px -32px' });
  revealItems.forEach((item) => observer.observe(item));
}
