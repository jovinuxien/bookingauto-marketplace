import React, { useEffect, useState } from 'react';
import axios from 'app/config/axiosinstance';
import type { ApiError } from 'app/config/axios-interceptor';
import { formatDay, formatTime } from 'app/shared/util/format';

interface Message {
  id: number;
  sender: 'customer' | 'provider';
  body: string;
  sentAt: string;
  fromCustomer: boolean;
}

/**
 * The conversation, on the page the mailed link opens (ADR 0019).
 *
 * Local state rather than the store: the thread belongs to this page and
 * this token, and nothing else on the site reads it.
 */
export const MessageThread = ({ token, providerName }: { token: string | null; providerName: string }) => {
  const [messages, setMessages] = useState<Message[] | null>(null);
  const [body, setBody] = useState('');
  const [sending, setSending] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  useEffect(() => {
    if (!token) return;
    axios.post<{ messages: Message[] }>('/bookings/messages/list', { token }).then(
      response => setMessages(response.data.messages),
      () => setMessages(null),
    );
  }, [token]);

  if (!token || messages === null) {
    return null;
  }

  const send = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!body.trim()) return;
    setSending(true);
    setProblem(null);
    try {
      const response = await axios.post<Message>('/bookings/messages', { token, body });
      setMessages([...messages, response.data]);
      setBody('');
    } catch (error) {
      const failure = error as ApiError;
      setProblem(typeof failure.data === 'string' ? failure.data : 'Meddelandet kunde inte skickas.');
    } finally {
      setSending(false);
    }
  };

  return (
    <div className="card card-body mb-3">
      <h2 className="h6">Meddelanden till {providerName}</h2>
      {messages.length === 0 && (
        <p className="text-muted small mb-2">
          Undrar du något inför besöket? Skriv här, så får {providerName} ett mejl.
        </p>
      )}
      {messages.length > 0 && (
        <ul className="list-unstyled mb-2">
          {messages.map(message => (
            <li key={message.id} className="mb-2">
              <div className="small text-muted">
                {message.fromCustomer ? 'Du' : providerName} · {formatDay(message.sentAt)}{' '}
                {formatTime(message.sentAt)}
              </div>
              <div className={message.fromCustomer ? '' : 'fw-medium'}>{message.body}</div>
            </li>
          ))}
        </ul>
      )}
      <form className="d-flex gap-2" onSubmit={send}>
        <textarea className="form-control form-control-sm" rows={2} maxLength={2000}
          placeholder="Skriv ett meddelande…" value={body} aria-label="Meddelande"
          onChange={event => setBody(event.target.value)} />
        <button className="btn btn-sm btn-outline-primary align-self-end" type="submit"
          disabled={sending || !body.trim()}>
          {sending ? 'Skickar…' : 'Skicka'}
        </button>
      </form>
      {problem && <div className="text-danger small mt-1">{problem}</div>}
    </div>
  );
};
