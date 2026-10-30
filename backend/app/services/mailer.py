"""Every email VisionMirror sends goes through here.

Rules:
* An email problem (bad SMTP login, provider outage) can NEVER fail the request that triggered it:
  `send_email` logs and returns False instead of raising.
* Sending runs in a worker thread (smtplib blocks) with an 8-second timeout, so the API stays responsive.
* All user-supplied text is HTML-escaped and subjects have newlines stripped.
* Logs name the purpose and a masked address, never the full recipient or a link.
* Emails carry nothing about what a person photographed or asked: only account and payment notices.

Delivery is Gmail SMTP for now (a domain-based provider such as Resend later: change only the EMAIL_*
settings). With no EMAIL_HOST_PASSWORD set (local development, tests) nothing is sent and the email is only logged.
"""

import logging
import re
import smtplib
import ssl
from email.message import EmailMessage
from html import escape

from starlette.concurrency import run_in_threadpool

from app.core.config import Settings

log = logging.getLogger("visionmirror.mailer")
BRAND = "VisionMirror"


def _mask(address: str) -> str:
    name, _, domain = (address or "").partition("@")
    return f"{name[:1]}***@{domain}"


def render(settings: Settings, headline: str, paragraphs: list[str], button: tuple[str, str] | None = None) -> tuple[str, str]:
    """(html, plain text) for a simple branded email. Every string is escaped here."""
    support = settings.support_email or settings.email_host_user
    body = "".join(f'<p style="margin:0 0 16px 0;">{escape(p)}</p>' for p in paragraphs)
    cta = ""
    if button:
        label, url = button
        cta = (
            f'<p style="margin:0 0 18px 0;"><a href="{escape(url, quote=True)}" style="display:inline-block;'
            f'background:#0b192c;color:#ffffff;text-decoration:none;padding:12px 20px;border-radius:10px;">{escape(label)}</a></p>'
            f'<p style="margin:0 0 8px 0;font-size:13px;color:#64748b;">If the button does not work, copy this link into your browser:</p>'
            f'<p style="margin:0;font-size:13px;word-break:break-all;">{escape(url)}</p>'
        )
    html = f"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head>
<body style="margin:0;padding:0;background:#f5f7fa;"><table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#f5f7fa;padding:24px 12px;"><tr><td align="center">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:560px;">
<tr><td style="padding:0 4px 16px 4px;font-family:Arial,Helvetica,sans-serif;font-size:22px;font-weight:bold;color:#0b192c;">{BRAND}</td></tr>
<tr><td style="background:#ffffff;border:1px solid #e2e8f0;border-radius:16px;padding:28px 26px;font-family:Arial,Helvetica,sans-serif;color:#0b192c;font-size:15px;line-height:1.55;">
<h1 style="margin:0 0 12px 0;font-size:22px;line-height:1.3;">{escape(headline)}</h1>{body}{cta}</td></tr>
<tr><td style="padding:16px 8px 0 8px;font-family:Arial,Helvetica,sans-serif;font-size:12px;line-height:1.5;color:#64748b;text-align:center;">Questions? Write to {escape(support)}.</td></tr>
</table></td></tr></table></body></html>"""
    lines = [headline, "", *paragraphs]
    if button:
        lines += ["", f"{button[0]}: {button[1]}"]
    return html, "\n".join(lines)


def _send_blocking(settings: Settings, to: str, subject: str, headline: str, paragraphs: list[str], button) -> bool:
    html, text = render(settings, headline, paragraphs, button)
    message = EmailMessage()
    message["Subject"] = " ".join(subject.split())
    message["From"] = settings.email_from or settings.email_host_user
    message["To"] = to
    message.set_content(text)
    message.add_alternative(html, subtype="html")
    if settings.email_use_ssl:
        with smtplib.SMTP_SSL(settings.email_host, settings.email_port, timeout=8, context=ssl.create_default_context()) as smtp:
            smtp.login(settings.email_host_user, settings.email_host_password)
            smtp.send_message(message)
    else:
        with smtplib.SMTP(settings.email_host, settings.email_port, timeout=8) as smtp:
            smtp.starttls(context=ssl.create_default_context())
            smtp.login(settings.email_host_user, settings.email_host_password)
            smtp.send_message(message)
    return True


async def send_email(settings: Settings, to: str, subject: str, headline: str, paragraphs: list[str],
                     button: tuple[str, str] | None = None) -> bool:
    """Sends one email. Never raises."""
    if not to:
        return False
    try:
        if not settings.email_host_password or not settings.email_host_user:
            log.info("Email not configured; would have sent '%s' to %s", re.sub(r"\s+", " ", subject), _mask(to))
            return False
        await run_in_threadpool(_send_blocking, settings, to, subject, headline, paragraphs, button)
        log.info("Sent '%s' to %s", re.sub(r"\s+", " ", subject), _mask(to))
        return True
    except Exception:  # noqa: BLE001 - email trouble must never break the caller
        log.exception("Could not send an email to %s", _mask(to))
        return False


def naira_from_kobo(kobo: int) -> str:
    return "₦{:,.2f}".format(int(kobo) / 100)


async def send_welcome(settings: Settings, email: str) -> bool:
    return await send_email(
        settings, email, "Welcome to VisionMirror", "Welcome to VisionMirror",
        ["Your account is ready.", "Open the app and point your camera: VisionMirror will tell you what it sees."],
    )


async def send_receipt(settings: Settings, email: str, amount_kobo: int, reference: str, purpose: str) -> bool:
    what = {"month": "a month of VisionMirror", "subscribe": "your VisionMirror subscription", "gift": "your support"}.get(purpose, "VisionMirror")
    return await send_email(
        settings, email, f"Your VisionMirror receipt: {naira_from_kobo(amount_kobo)}", "Payment received",
        [
            f"Thank you for {what}. Here is your receipt.",
            f"Amount: {naira_from_kobo(amount_kobo)}",
            f"Reference: {reference}",
            "Keep this email as your record.",
        ],
    )


async def send_password_reset(settings: Settings, email: str, link: str) -> bool:
    return await send_email(
        settings, email, "Reset your VisionMirror password", "Reset your password",
        [
            "Someone asked to reset the password for this account. If that was you, choose a new one with the link below. "
            "It works once and expires in one hour.",
            "If you did not ask for this, ignore this email. Your password will not change.",
        ],
        ("Choose a new password", link),
    )
