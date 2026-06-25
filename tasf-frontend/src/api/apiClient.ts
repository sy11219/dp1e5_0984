import axios from "axios";

export const api = axios.create({
  baseURL: "/api",
});

api.interceptors.response.use(
  (response) => response,
  (error) => {
    const message = error?.response?.data?.error;
    if (typeof message === "string" && message.trim()) {
      return Promise.reject(new Error(message));
    }
    return Promise.reject(error);
  }
);
