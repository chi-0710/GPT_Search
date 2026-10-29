import { NextResponse } from 'next/server';

export async function POST(request: Request) {
  const backendUrl = process.env.INTERNAL_API_URL ||
    process.env.NEXT_PUBLIC_GPTR_API_URL ||
    'http://localhost:8000';
  
  try {
    // Parse the request body
    let body;
    try {
      body = await request.json();
    } catch (parseError) {
      console.error('Error parsing request body:', parseError);
      return NextResponse.json(
        { error: '请求正文中的 JSON 格式无效' },
        { status: 400 }
      );
    }
    
    console.log(`POST /api/chat - Proxying request to backend`);
    
    const response = await fetch(`${backendUrl}/api/chat`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(body),
    });
    
    const data = await response.json();
    return NextResponse.json(data, { status: response.status });
  } catch (error: any) {
    console.error('POST /api/chat - Error proxying to backend:', error);
    return NextResponse.json(
      { error: '无法连接到后端服务' },
      { status: 500 }
    );
  }
}
