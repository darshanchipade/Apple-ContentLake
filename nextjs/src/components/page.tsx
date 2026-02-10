"use client";

import { useEffect, useState } from "react";

export default function Home() {
  const [msg, setMsg] = useState("");

  useEffect(() => {
    fetch(`${process.env.SPRINGBOOT_BASE_URL}/api/hello`)
      .then(r => r.text())
      .then(setMsg);
  }, []);

  return <div>Response from backend: {msg}</div>;
}
