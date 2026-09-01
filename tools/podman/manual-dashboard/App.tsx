import React, { useCallback, useEffect, useRef, useState } from 'react';

const buttonKeys: Record<string, string> = {
  q: 'back',
  ArrowLeft: 'back',
  w: 'up',
  ArrowUp: 'up',
  s: 'select',
  ArrowRight: 'select',
  x: 'down',
  ArrowDown: 'down',
};

const panel: React.CSSProperties = {
  background: '#fff',
  border: '1px solid #d7dde5',
  borderRadius: 12,
  boxShadow: '0 2px 8px rgba(20, 32, 48, 0.08)',
  padding: 16,
};

const buttonStyle: React.CSSProperties = {
  minHeight: 44,
  padding: '8px 14px',
  border: '1px solid #aeb8c5',
  borderRadius: 8,
  background: '#f5f7fa',
  color: '#17202b',
  cursor: 'pointer',
  fontWeight: 600,
};

const inputStyle: React.CSSProperties = {
  minHeight: 42,
  boxSizing: 'border-box',
  border: '1px solid #aeb8c5',
  borderRadius: 8,
  padding: '8px 10px',
  width: '100%',
};

async function api(path: string, body?: object) {
  const response = await fetch(path, body ? {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  } : { cache: 'no-store' });
  const result = await response.json();
  if (!response.ok || !result.ok) {
    throw new Error(result.error || `${response.status} ${response.statusText}`);
  }
  return result;
}

function App() {
  const [status, setStatus] = useState<any>({});
  const [androidState, setAndroidState] = useState('connecting');
  const [message, setMessage] = useState('Starting manual lab…');
  const [bpm, setBpm] = useState('123');
  const [quality, setQuality] = useState('excellent');
  const [steps, setSteps] = useState('1000');
  const [captureName, setCaptureName] = useState('manual');
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const drawingRef = useRef(false);
  const androidCanvasRef = useRef<HTMLCanvasElement>(null);
  const androidDrawingRef = useRef(false);
  const androidSizeRef = useRef({ width: 0, height: 0 });
  const androidPointerRef = useRef<{ x: number; y: number; started: number } | null>(null);

  const pressButton = useCallback(async (button: string) => {
    try {
      await api('/lab-api/button', { button });
      setMessage(`${button[0].toUpperCase()}${button.slice(1)} pressed`);
    } catch (error) {
      setMessage(String(error));
    }
  }, []);

  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      if (event.repeat || target?.isContentEditable || ['INPUT', 'SELECT', 'TEXTAREA'].includes(target?.tagName || '')) {
        return;
      }
      const button = buttonKeys[event.key] || buttonKeys[event.key.toLowerCase()];
      if (button) {
        event.preventDefault();
        void pressButton(button);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [pressButton]);

  useEffect(() => {
    let cancelled = false;
    let timer: number | undefined;
    const refresh = async () => {
      try {
        const next = await api('/lab-api/status');
        if (!cancelled) {
          setStatus(next);
          setMessage(next.ready ? 'Lab ready; Locus recording is stopped.' : 'Waiting for every service to become ready…');
        }
      } catch (error) {
        if (!cancelled) setMessage(String(error));
      } finally {
        if (!cancelled) timer = window.setTimeout(refresh, 2000);
      }
    };
    void refresh();
    return () => {
      cancelled = true;
      if (timer !== undefined) window.clearTimeout(timer);
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    const draw = async () => {
      if (cancelled || drawingRef.current || document.hidden) return;
      drawingRef.current = true;
      try {
        const response = await fetch(`/lab-api/pebble-frame.png?t=${Date.now()}`, { cache: 'no-store' });
        if (!response.ok) throw new Error(`Pebble frame: ${response.status}`);
        const bitmap = await createImageBitmap(await response.blob());
        const canvas = canvasRef.current;
        if (canvas) {
          canvas.width = bitmap.width;
          canvas.height = bitmap.height;
          const context = canvas.getContext('2d');
          context?.drawImage(bitmap, 0, 0);
        }
        bitmap.close();
      } catch (error) {
        if (!cancelled) setMessage(String(error));
      } finally {
        drawingRef.current = false;
      }
    };
    void draw();
    const timer = window.setInterval(draw, 500);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    const draw = async () => {
      if (cancelled || androidDrawingRef.current || document.hidden) return;
      androidDrawingRef.current = true;
      try {
        const response = await fetch(`/lab-api/android-frame.png?t=${Date.now()}`, { cache: 'no-store' });
        if (!response.ok) throw new Error(`Android frame: ${response.status}`);
        androidSizeRef.current = {
          width: Number(response.headers.get('X-Android-Width')),
          height: Number(response.headers.get('X-Android-Height')),
        };
        const bitmap = await createImageBitmap(await response.blob());
        const canvas = androidCanvasRef.current;
        if (canvas) {
          canvas.width = bitmap.width;
          canvas.height = bitmap.height;
          canvas.getContext('2d')?.drawImage(bitmap, 0, 0);
          setAndroidState('connected');
        }
        bitmap.close();
      } catch (error) {
        if (!cancelled) {
          setAndroidState('error');
          setMessage(String(error));
        }
      } finally {
        androidDrawingRef.current = false;
      }
    };
    void draw();
    const timer = window.setInterval(draw, 750);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, []);

  const androidPoint = (event: React.PointerEvent<HTMLCanvasElement>) => {
    const canvas = event.currentTarget;
    const rect = canvas.getBoundingClientRect();
    const native = androidSizeRef.current;
    return {
      x: Math.round((event.clientX - rect.left) * (native.width || canvas.width) / rect.width),
      y: Math.round((event.clientY - rect.top) * (native.height || canvas.height) / rect.height),
    };
  };

  const androidPointerDown = (event: React.PointerEvent<HTMLCanvasElement>) => {
    event.currentTarget.setPointerCapture(event.pointerId);
    androidPointerRef.current = { ...androidPoint(event), started: Date.now() };
  };

  const androidPointerUp = async (event: React.PointerEvent<HTMLCanvasElement>) => {
    const start = androidPointerRef.current;
    androidPointerRef.current = null;
    if (!start) return;
    const end = androidPoint(event);
    try {
      await api('/lab-api/android/touch', {
        x1: start.x,
        y1: start.y,
        x2: end.x,
        y2: end.y,
        duration_ms: Math.min(5000, Date.now() - start.started),
      });
    } catch (error) {
      setMessage(String(error));
    }
  };

  const submit = async (path: string, body: object, success: string) => {
    try {
      await api(path, body);
      setMessage(success);
    } catch (error) {
      setMessage(String(error));
    }
  };

  const capture = async (kind: 'android' | 'pebble') => {
    try {
      const result = await api(`/lab-api/capture/${kind}`, { name: captureName });
      const link = document.createElement('a');
      link.href = result.download_url;
      link.download = result.filename;
      document.body.appendChild(link);
      link.click();
      link.remove();
      setMessage(`Saved ${result.filename}`);
    } catch (error) {
      setMessage(String(error));
    }
  };

  const statuses = [
    ['Android', status.android_ready],
    ['Android display', androidState === 'connected'],
    ['Locus available', status.locus_available === 'true'],
    ['Recording stopped', status.recording_state === 'STOPPED'],
    ['Pebble QEMU', status.qemu_connected],
    ['Pebble App link', status.phone_connected && status.watch_connected === 'true'],
    ['TrackGlance open', status.watch_app_open === 'true'],
  ];

  return (
    <main style={{ maxWidth: 1500, margin: '0 auto', padding: 20, color: '#17202b', fontFamily: 'system-ui, sans-serif' }}>
      <header style={{ marginBottom: 16 }}>
        <h1 style={{ marginBottom: 4 }}>TrackGlance emulator lab</h1>
        <div role="status" style={{ color: status.ready ? '#176b37' : '#8a5310' }}>{message}</div>
      </header>

      <section style={{ ...panel, marginBottom: 16 }} aria-label="Readiness">
        <h2 style={{ marginTop: 0 }}>Readiness · {status.platform || 'starting'}</h2>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          {statuses.map(([label, ready]) => (
            <span key={String(label)} style={{ padding: '6px 10px', borderRadius: 999, background: ready ? '#dff5e7' : '#fff0d7', color: ready ? '#176b37' : '#74430a' }}>
              {ready ? 'Ready' : 'Waiting'} · {label}
            </span>
          ))}
        </div>
      </section>

      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(380px, 1fr) minmax(300px, 0.75fr)', gap: 16, alignItems: 'start' }}>
        <section style={panel}>
          <h2 style={{ marginTop: 0 }}>Android</h2>
          <div style={{ background: '#111', borderRadius: 12, padding: 10, width: 'fit-content', maxWidth: '100%' }}>
            <canvas
              ref={androidCanvasRef}
              aria-label="Live Android emulator screen"
              onPointerDown={androidPointerDown}
              onPointerUp={(event) => void androidPointerUp(event)}
              onPointerCancel={() => { androidPointerRef.current = null; }}
              style={{ height: 640, maxHeight: '70vh', maxWidth: '100%', touchAction: 'none', display: 'block' }}
            />
          </div>
          <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
            {['back', 'home', 'overview'].map((key) => (
              <button key={key} style={buttonStyle} onClick={() => void submit('/lab-api/android/key', { key }, `${key} pressed`)}>
                {key[0].toUpperCase()}{key.slice(1)}
              </button>
            ))}
          </div>
        </section>

        <section style={panel}>
          <h2 style={{ marginTop: 0 }}>Pebble {status.platform || ''}</h2>
          <div style={{ display: 'flex', justifyContent: 'center', background: '#111', borderRadius: status.platform === 'gabbro' ? '50%' : 12, padding: 16 }}>
            <canvas ref={canvasRef} aria-label="Live Pebble emulator screen" style={{ width: status.platform === 'gabbro' ? 240 : 228, maxWidth: '100%', imageRendering: 'pixelated', borderRadius: status.platform === 'gabbro' ? '50%' : 0 }} />
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8, marginTop: 12 }}>
            {['back', 'up', 'select', 'down'].map((name) => (
              <button key={name} style={buttonStyle} onClick={() => void pressButton(name)}>
                {name[0].toUpperCase()}{name.slice(1)}
              </button>
            ))}
          </div>
          <p style={{ color: '#52606f', fontSize: 14 }}>Q/W/S/X or ←/↑/→/↓ map to Back/Up/Select/Down.</p>
        </section>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: 16, marginTop: 16 }}>
        <section style={panel}>
          <h2 style={{ marginTop: 0 }}>Synthetic heart rate</h2>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
            <input aria-label="Heart-rate value" style={inputStyle} type="number" min="0" max="255" value={bpm} onChange={(event) => setBpm(event.target.value)} />
            <select aria-label="Heart-rate quality" style={inputStyle} value={quality} onChange={(event) => setQuality(event.target.value)}>
              {['off-wrist', 'worst', 'poor', 'acceptable', 'good', 'excellent'].map((value) => <option key={value}>{value}</option>)}
            </select>
          </div>
          <button style={{ ...buttonStyle, marginTop: 8 }} onClick={() => void submit('/lab-api/heart-rate', { bpm: Number(bpm), quality }, 'Heart rate injected')}>Send heart rate</button>
        </section>

        <section style={panel}>
          <h2 style={{ marginTop: 0 }}>Absolute steps</h2>
          <input aria-label="Absolute step count" style={inputStyle} type="number" min="0" max="2147483647" value={steps} onChange={(event) => setSteps(event.target.value)} />
          <button style={{ ...buttonStyle, marginTop: 8 }} onClick={() => void submit('/lab-api/steps', { count: Number(steps) }, 'Step count injected')}>Send steps</button>
        </section>

        <section style={panel}>
          <h2 style={{ marginTop: 0 }}>PNG captures</h2>
          <input aria-label="Capture name" style={inputStyle} pattern="[A-Za-z0-9][A-Za-z0-9._-]{0,63}" value={captureName} onChange={(event) => setCaptureName(event.target.value)} />
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 8 }}>
            <button style={buttonStyle} onClick={() => void capture('android')}>Capture Android</button>
            <button style={buttonStyle} onClick={() => void capture('pebble')}>Capture Pebble</button>
          </div>
        </section>
      </div>
    </main>
  );
}

export default App;
