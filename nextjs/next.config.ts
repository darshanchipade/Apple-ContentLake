import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  devIndicators: false,
  images: {
    remotePatterns: [
      {
        protocol: "https",
        hostname: "ea854xr24n6.exactdn.com",
      },
    ],
  },
};

export default nextConfig;
