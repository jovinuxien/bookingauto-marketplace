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

/** The booking's thread, inside the console row. The customer gets a mail per reply (ADR 0019). */
const ConsoleThread = ({ bookingId, customerName }: { bookingId: number; customerName: string }) => {
  const [messages, setMessages] = useState<Message[] | null>(null);
  const [body, setBody] = useState('');
  const [sending, setSending] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  useEffect(() => {
    axios.get<Message[]>(`/console/bookings/${bookingId}/messages`).then(
      response => setMessages(response.data),
      () => setProblem('Kunde inte hämta meddelandena.'),
    );
  }, [bookingId]);

  const send = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!body.trim() || messages === null) return;
    setSending(true);
    setProblem(null);
    try {
      const response = await axios.post<Message>(`/console/bookings/${bookingId}/messages`, { body });
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
    <div className="border rounded p-2 mt-1 bg-light" style={{ maxWidth: '28rem' }}>
      {messages === null && !problem && <div className="small text-muted">Hämtar…</div>}
      {messages && messages.length === 0 && (
        <div className="small text-muted mb-1">Inga meddelanden ännu. Kunden får ett mejl när du skriver.</div>
      )}
      {messages && messages.map(message => (
        <div key={message.id} className="mb-1">
          <span className="small text-muted">
            {message.fromCustomer ? customerName : 'Du'} · {formatDay(message.sentAt)} {formatTime(message.sentAt)}:{' '}
          </span>
          <span className="small">{message.body}</span>
        </div>
      ))}
      <form className="d-flex gap-1 mt-1" onSubmit={send}>
        <input className="form-control form-control-sm" maxLength={2000} value={body}
          placeholder="Svara…" aria-label="Svar till kunden"
          onChange={event => setBody(event.target.value)} />
        <button className="btn btn-sm btn-outline-primary" type="submit" disabled={sending || !body.trim()}>
          Skicka
        </button>
      </form>
      {problem && <div className="text-danger small mt-1">{problem}</div>}
    </div>
  );
};

export default ConsoleThread;
