import { NextResponse } from 'next/server';

export async function GET(
  request: Request,
  { params }: { params: { id: string } }
) {
  const { id } = params;
  const backendUrl = process.env.INTERNAL_API_URL ||
    process.env.NEXT_PUBLIC_GPTR_API_URL ||
    'http://localhost:8000';
  
  try {
    console.log(`GET /api/reports/${id} - Proxying request to backend`);
    
    const response = await fetch(`${backendUrl}/api/reports/${id}`);
    
    if (!response.ok) {
      // Handle backend errors
      const errorData = await response.json().catch(() => ({ detail: `后端错误 ${response.status}` }));
      return NextResponse.json(
        { error: errorData.detail || '获取研究报告失败' },
        { status: response.status }
      );
    }
    
    const data = await response.json();
    return NextResponse.json(data, { status: 200 });
  } catch (error) {
    console.error(`GET /api/reports/${id} - Error proxying to backend:`, error);
    return NextResponse.json(
      { error: '无法连接到后端服务' },
      { status: 500 }
    );
  }
}

export async function DELETE(
  request: Request,
  { params }: { params: { id: string } }
) {
  const { id } = params;
  const backendUrl = process.env.INTERNAL_API_URL ||
    process.env.NEXT_PUBLIC_GPTR_API_URL ||
    'http://localhost:8000';
  
  try {
    console.log(`DELETE /api/reports/${id} - Proxying request to backend`);
    
    const response = await fetch(`${backendUrl}/api/reports/${id}`, {
      method: 'DELETE',
    });
    
    if (!response.ok && response.status !== 404) {
      // Handle backend errors
      const errorData = await response.json().catch(() => ({ detail: `后端错误 ${response.status}` }));
      return NextResponse.json(
        { error: errorData.detail || '删除研究报告失败' },
        { status: response.status }
      );
    }
    
    return NextResponse.json({ success: true }, { status: 200 });
  } catch (error) {
    console.error(`DELETE /api/reports/${id} - Error proxying to backend:`, error);
    return NextResponse.json(
      { error: '无法连接到后端服务' },
      { status: 500 }
    );
  }
}

export async function PUT(
  request: Request,
  { params }: { params: { id: string } }
) {
  const { id } = params;
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
    
    console.log(`PUT /api/reports/${id} - Proxying request to backend`);
    
    const response = await fetch(`${backendUrl}/api/reports/${id}`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(body),
    });
    
    if (!response.ok) {
      // Handle backend errors
      const errorData = await response.json().catch(() => ({ detail: `后端错误 ${response.status}` }));
      return NextResponse.json(
        { error: errorData.detail || '更新研究报告失败' },
        { status: response.status }
      );
    }
    
    const data = await response.json();
    return NextResponse.json(data, { status: 200 });
  } catch (error) {
    console.error(`PUT /api/reports/${id} - Error proxying to backend:`, error);
    return NextResponse.json(
      { error: '无法连接到后端服务' },
      { status: 500 }
    );
  }
}
